package com.urlxl.mail.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePairingDeepLinkParserTest {

    @Test
    fun parse_validDeepLink_extractsRequiredParams() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://native-pair?sub=subscriber-123&hash=secureHash&srv=https%3A%2F%2Fserver.example.com" +
                "&reg=https%3A%2F%2Fserver.example.com%2Fapi%2Fnotifications%2Fnative%2Fregister&pt=short-lived-token",
            nowEpochMs = 123L,
        )

        assertTrue(result is PairingParseResult.Success)
        val pairing = (result as PairingParseResult.Success).pairing
        assertEquals("subscriber-123", pairing.subscriberId)
        assertEquals("secureHash", pairing.subscriberHash)
        assertEquals("https://server.example.com", pairing.serverUrl)
        assertEquals("https://server.example.com/api/notifications/native/register", pairing.registrationUrl)
        assertEquals("short-lived-token", pairing.pairingToken)
        assertEquals(null, pairing.deviceId)
        assertEquals(123L, pairing.pairedAtEpochMs)
    }

    @Test
    fun parse_missingReg_leavesRegistrationUrlBlank() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://native-pair?sub=subscriber-123&hash=secureHash&srv=https%3A%2F%2Fserver.example.com&pt=token",
        )

        val pairing = (result as PairingParseResult.Success).pairing
        assertEquals("", pairing.registrationUrl)
    }

    @Test
    fun parse_missingPairingToken_returnsError() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://native-pair?sub=subscriber-123&hash=secureHash&srv=https%3A%2F%2Fserver.example.com",
        )

        assertTrue(result is PairingParseResult.Error)
        assertEquals("Missing pairing token", (result as PairingParseResult.Error).reason)
    }

    @Test
    fun parse_missingServerUrl_returnsError() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://native-pair?sub=subscriber-123&hash=secureHash&pt=token",
        )

        assertTrue(result is PairingParseResult.Error)
        assertEquals("Missing server URL", (result as PairingParseResult.Error).reason)
    }

    @Test
    fun parse_legacyNovuPairScheme_isRejected() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://novu-pair?sub=a&hash=b&srv=https%3A%2F%2Fserver.example.com&pt=c",
        )

        assertTrue(result is PairingParseResult.Error)
    }

    @Test
    fun parse_invalidHost_returnsError() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://other-host?sub=a&hash=b&srv=https%3A%2F%2Fserver.example.com&pt=c",
        )

        assertTrue(result is PairingParseResult.Error)
    }

    @Test
    fun parse_httpServerUrl_returnsError() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://native-pair?sub=a&hash=b&srv=http%3A%2F%2Fserver.example.com&pt=c",
        )

        assertTrue(result is PairingParseResult.Error)
        assertEquals("Server URL must use https", (result as PairingParseResult.Error).reason)
    }

    @Test
    fun parse_httpRegistrationUrl_returnsError() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://native-pair?sub=a&hash=b&srv=https%3A%2F%2Fserver.example.com" +
                "&reg=http%3A%2F%2Fserver.example.com%2Fregister&pt=c",
        )

        assertTrue(result is PairingParseResult.Error)
        assertEquals("Registration URL must use https", (result as PairingParseResult.Error).reason)
    }

    @Test
    fun parse_schemelessServerUrl_returnsError() {
        val result = NativePairingDeepLinkParser.parse(
            "llamalabels://native-pair?sub=a&hash=b&srv=server.example.com&pt=c",
        )

        assertTrue(result is PairingParseResult.Error)
    }
}
