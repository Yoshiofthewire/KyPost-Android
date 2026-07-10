package com.urlxl.mail.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MfaChallengePayloadParserTest {

    @Test
    fun parse_readsContractKeysExactly() {
        val payload = MfaChallengePayloadParser.parse(
            mapOf(
                "type" to "mfa_challenge",
                "challengeId" to "ch-123",
            ),
        )
        requireNotNull(payload)
        assertEquals("ch-123", payload.challengeId)
    }

    @Test
    fun parse_wrongType_returnsNull() {
        val payload = MfaChallengePayloadParser.parse(
            mapOf(
                "type" to "something_else",
                "challengeId" to "ch-123",
            ),
        )
        assertNull(payload)
    }

    @Test
    fun parse_missingType_returnsNull() {
        val payload = MfaChallengePayloadParser.parse(mapOf("challengeId" to "ch-123"))
        assertNull(payload)
    }

    @Test
    fun parse_blankChallengeId_returnsNull() {
        val payload = MfaChallengePayloadParser.parse(
            mapOf("type" to "mfa_challenge", "challengeId" to "   "),
        )
        assertNull(payload)
    }
}
