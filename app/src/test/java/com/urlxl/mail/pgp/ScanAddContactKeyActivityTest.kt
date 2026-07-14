package com.urlxl.mail.pgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers [ScanAddContactKeyActivity.parsePgpQrKeyUrl] — a pure function with no Android
 *  framework dependency, so it's plain-JVM testable like [PgpQrClientTest]'s coverage of
 *  [PgpQrClient]. No mocking framework, matching this repo's house style. */
class ScanAddContactKeyActivityTest {

    @Test
    fun parsePgpQrKeyUrl_validUrl_extractsServerUrlAndToken() {
        val parsed = ScanAddContactKeyActivity.parsePgpQrKeyUrl(
            "https://mail.example.com/api/pgp/qr/key?t=abc123",
        )

        assertEquals(ParsedPgpQrKeyUrl(serverUrl = "https://mail.example.com", token = "abc123"), parsed)
    }

    @Test
    fun parsePgpQrKeyUrl_malformedString_returnsNull() {
        val parsed = ScanAddContactKeyActivity.parsePgpQrKeyUrl("not a url at all")

        assertNull(parsed)
    }

    @Test
    fun parsePgpQrKeyUrl_wrongPath_returnsNull() {
        val parsed = ScanAddContactKeyActivity.parsePgpQrKeyUrl(
            "https://mail.example.com/api/pgp/qr/token?t=abc123",
        )

        assertNull(parsed)
    }

    @Test
    fun parsePgpQrKeyUrl_missingTParam_returnsNull() {
        val parsed = ScanAddContactKeyActivity.parsePgpQrKeyUrl(
            "https://mail.example.com/api/pgp/qr/key",
        )

        assertNull(parsed)
    }

    @Test
    fun parsePgpQrKeyUrl_blankTParam_returnsNull() {
        val parsed = ScanAddContactKeyActivity.parsePgpQrKeyUrl(
            "https://mail.example.com/api/pgp/qr/key?t=",
        )

        assertNull(parsed)
    }
}
