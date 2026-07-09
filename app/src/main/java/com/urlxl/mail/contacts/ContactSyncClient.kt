package com.urlxl.mail.contacts

import com.urlxl.mail.executeSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

sealed class ContactSyncResult {
    data class Success(val response: ContactSyncPullResponseDto) : ContactSyncResult()
    data class Unauthorized(val message: String) : ContactSyncResult()
    data class BadRequest(val message: String) : ContactSyncResult()
    data class ServiceUnavailable(val message: String) : ContactSyncResult()
    data class Retryable(val message: String) : ContactSyncResult()
}

/**
 * Talks to `/api/contacts/sync`. Auth is `sub`/`hash` query params only (never headers/cookies),
 * kept parallel to [com.urlxl.mail.push.PullNotificationClient] — same okhttp/serialization stack.
 */
class ContactSyncClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun pull(serverUrl: String, subscriberId: String, subscriberHash: String, since: Long): ContactSyncResult {
        val base = syncUrl(serverUrl) ?: return ContactSyncResult.BadRequest("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .addQueryParameter("since", since.coerceAtLeast(0L).toString())
            .build()
        return execute(Request.Builder().url(url).get().build())
    }

    suspend fun push(
        serverUrl: String,
        subscriberId: String,
        subscriberHash: String,
        baseCursor: Long,
        changes: List<ContactDto>,
    ): ContactSyncResult {
        val base = syncUrl(serverUrl) ?: return ContactSyncResult.BadRequest("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .build()
        val body = json.encodeToString(ContactSyncPushRequestDto(baseCursor = baseCursor, changes = changes))
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        return execute(request)
    }

    private fun syncUrl(serverUrl: String) = "${serverUrl.trimEnd('/')}/api/contacts/sync".toHttpUrlOrNull()

    private suspend fun execute(request: Request): ContactSyncResult {
        val result = withContext(Dispatchers.IO) {
            okHttpClient.executeSync(request) { response -> response.code to response.body?.string().orEmpty() }
        }
        val (code, rawBody) = result.getOrNull()
            ?: return ContactSyncResult.Retryable(result.exceptionOrNull()?.message ?: "Network error during contact sync")

        return when (code) {
            200 -> {
                val parsed = runCatching { json.decodeFromString<ContactSyncPullResponseDto>(rawBody) }.getOrNull()
                    ?: return ContactSyncResult.Retryable("Malformed contact sync response")
                ContactSyncResult.Success(parsed)
            }
            400 -> ContactSyncResult.BadRequest(rawBody.ifBlank { "Malformed request" })
            401 -> ContactSyncResult.Unauthorized("Bad hash or unknown subscriber")
            503 -> ContactSyncResult.ServiceUnavailable("Contact sync is not configured on the backend")
            else -> ContactSyncResult.Retryable("Contact sync failed ($code)")
        }
    }
}
