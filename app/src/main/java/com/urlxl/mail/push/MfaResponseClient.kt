package com.urlxl.mail.push

import com.urlxl.mail.executeSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

@Serializable
data class MfaRespondRequest(
    @SerialName("challengeId") val challengeId: String,
    @SerialName("subscriberId") val subscriberId: String,
    @SerialName("subscriberHash") val subscriberHash: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("approve") val approve: Boolean,
)

@Serializable
data class MfaRespondResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("status") val status: String? = null,
)

/** Mirrors [resolvePullEndpoint] in NativeRegistration.kt — the respond endpoint has no server-provided override, it's always derived from the paired server URL. */
fun resolveMfaRespondEndpoint(serverUrl: String): String =
    "${serverUrl.trimEnd('/')}/api/mfa/push/respond"

sealed class MfaRespondResult {
    data class Success(val status: String) : MfaRespondResult()
    data class Error(val message: String) : MfaRespondResult()
}

class MfaResponseClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun respond(pairing: PairingData, challengeId: String, approve: Boolean): MfaRespondResult {
        val deviceId = pairing.deviceId
        if (deviceId.isNullOrBlank()) return MfaRespondResult.Error("Device is not registered yet")

        val request = MfaRespondRequest(
            challengeId = challengeId,
            subscriberId = pairing.subscriberId,
            subscriberHash = pairing.subscriberHash,
            deviceId = deviceId,
            approve = approve,
        )
        val httpRequest = Request.Builder()
            .url(resolveMfaRespondEndpoint(pairing.serverUrl))
            .post(json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val result = withContext(Dispatchers.IO) {
            okHttpClient.executeSync(httpRequest) { response -> response.code to response.body?.string().orEmpty() }
        }
        val (code, rawBody) = result.getOrNull()
            ?: return MfaRespondResult.Error(result.exceptionOrNull()?.message ?: "Failed to reach server")

        return when (code) {
            200 -> {
                val body = runCatching { json.decodeFromString<MfaRespondResponse>(rawBody) }.getOrNull()
                if (body?.ok == true) {
                    MfaRespondResult.Success(body.status ?: "resolved")
                } else {
                    MfaRespondResult.Error("Server did not confirm response")
                }
            }
            401 -> MfaRespondResult.Error("Pairing is no longer valid")
            403 -> MfaRespondResult.Error("This device cannot approve sign-in")
            409 -> {
                val body = runCatching { json.decodeFromString<MfaRespondResponse>(rawBody) }.getOrNull()
                MfaRespondResult.Error("Already ${body?.status ?: "resolved"} on another device")
            }
            else -> MfaRespondResult.Error("Failed to respond ($code)")
        }
    }
}
