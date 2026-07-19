package com.urlxl.mail.push

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Serializable
data class PairingData(
    val subscriberId: String,
    val subscriberHash: String,
    val serverUrl: String,
    val registrationUrl: String,
    val pairingToken: String,
    val deviceId: String?,
    val pairedAtEpochMs: Long,
)

object NativeRegistrationEndpointResolver {
    sealed class Resolution {
        data class Resolved(val registrationUrl: String) : Resolution()
        object MissingServerUrl : Resolution()
    }

    fun resolve(qrReg: String?, qrServerUrl: String?): Resolution {
        val reg = qrReg?.takeIf { it.isNotBlank() }
        if (reg != null) return Resolution.Resolved(reg)

        val srv = qrServerUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?: return Resolution.MissingServerUrl

        return Resolution.Resolved("$srv/api/notifications/native/register")
    }
}

data class PairingValidationResult(
    val isValid: Boolean,
    val message: String? = null,
)

sealed class PairingParseResult {
    data class Success(val pairing: PairingData) : PairingParseResult()
    data class Error(val reason: String) : PairingParseResult()
}

object PairingValidator {
    fun validate(sub: String, hash: String): PairingValidationResult {
        if (sub.isBlank()) return PairingValidationResult(false, "Missing sub parameter")
        if (hash.isBlank()) return PairingValidationResult(false, "Missing hash parameter")
        return PairingValidationResult(true)
    }
}

object NativePairingDeepLinkParser {
    fun parse(link: String, nowEpochMs: Long = System.currentTimeMillis()): PairingParseResult {
        val uri = runCatching { URI(link.trim()) }.getOrNull()
            ?: return PairingParseResult.Error("Invalid deep link")

        if (!uri.scheme.equals("llamalabels", ignoreCase = true) ||
            !uri.host.equals("native-pair", ignoreCase = true)
        ) {
            return PairingParseResult.Error("Unsupported deep link")
        }

        val query = parseQuery(uri.rawQuery.orEmpty())

        val sub = query["sub"].orEmpty().trim()
        val hash = query["hash"].orEmpty().trim()
        val srv = query["srv"].orEmpty().trim()
        val reg = query["reg"].orEmpty().trim().takeIf { it.isNotBlank() }
        val pt = query["pt"].orEmpty().trim()

        val validation = PairingValidator.validate(sub = sub, hash = hash)
        if (!validation.isValid) {
            return PairingParseResult.Error(validation.message ?: "Invalid pairing parameters")
        }
        if (pt.isBlank()) {
            return PairingParseResult.Error("Missing pairing token")
        }
        if (srv.isBlank()) {
            return PairingParseResult.Error("Missing server URL")
        }
        // The server is arbitrary (self-hosted relays, no fixed domain to allowlist), so https-only
        // is the one property we can enforce — it stops a plain-http deep link/QR from pointing the
        // device's pairing token and subscriber credentials at an unencrypted, spoofable endpoint.
        if (!isHttpsUrl(srv)) {
            return PairingParseResult.Error("Server URL must use https")
        }
        if (reg != null && !isHttpsUrl(reg)) {
            return PairingParseResult.Error("Registration URL must use https")
        }

        return PairingParseResult.Success(
            PairingData(
                subscriberId = sub,
                subscriberHash = hash,
                serverUrl = srv,
                registrationUrl = reg.orEmpty(),
                pairingToken = pt,
                deviceId = null,
                pairedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index < 0) return@mapNotNull null
                val key = decode(part.substring(0, index))
                val value = decode(part.substring(index + 1))
                key to value
            }
            .toMap()
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun isHttpsUrl(value: String): Boolean {
        val parsed = runCatching { URI(value) }.getOrNull() ?: return false
        return parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
    }
}
