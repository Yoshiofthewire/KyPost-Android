package com.urlxl.mail.pgp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [TEST_KEY] is a disposable ed25519 key generated with `gpg --quick-generate-key` purely as a
 *  fixture — [TEST_KEY_FINGERPRINT] is gpg's own reported fingerprint for it, letting these tests
 *  confirm [PgpFingerprint.compute] agrees with a real, independent OpenPGP implementation rather
 *  than just round-tripping through the same Bouncy Castle code it's built on. */
class PgpFingerprintTest {

    @Test
    fun compute_validArmoredKey_matchesFingerprintReportedByGpg() {
        val fingerprint = PgpFingerprint.compute(TEST_KEY)

        assertEquals(TEST_KEY_FINGERPRINT, fingerprint)
    }

    @Test
    fun compute_isDeterministic() {
        assertEquals(PgpFingerprint.compute(TEST_KEY), PgpFingerprint.compute(TEST_KEY))
    }

    @Test
    fun compute_blank_returnsNull() {
        assertNull(PgpFingerprint.compute(""))
    }

    @Test
    fun compute_notPgpArmor_returnsNull() {
        assertNull(PgpFingerprint.compute("this is not a pgp key at all"))
    }

    @Test
    fun compute_headerWithNoKeyData_returnsNull() {
        // A server (or MITM) sending a corrupted/truncated key must be rejected, not silently
        // hashed into some other value that still renders as a plausible-looking fingerprint.
        val headerOnly = TEST_KEY.lineSequence().take(2).joinToString("\n")

        assertNull(PgpFingerprint.compute(headerOnly))
    }

    @Test
    fun compute_corruptedKeyBody_returnsNull() {
        val corrupted = TEST_KEY.replaceFirst(
            "mDMEalxKSBYJKwYBBAHaRw8BAQdAaLBvayt/AqeBFCxDOrvjb36gwol5tI+JU+6p",
            "mDMEalxKSBYJKwYBBAHaRw8BAQdAaLBvayt/AqeBFCxDOrvjb36gwol5tI+JU+6X",
        )

        assertNull(PgpFingerprint.compute(corrupted))
    }

    private companion object {
        const val TEST_KEY_FINGERPRINT = "164D 5B83 4E7F E927 2DC7 293B 6D78 ABF3 D917 9534"
        val TEST_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            mDMEalxKSBYJKwYBBAHaRw8BAQdAaLBvayt/AqeBFCxDOrvjb36gwol5tI+JU+6p
            vOR9sTO0KVBncEZpbmdlcnByaW50VGVzdCA8dGVzdEBleGFtcGxlLmludmFsaWQ+
            iJAEExYKADgWIQQWTVuDTn/pJy3HKTtteKvz2ReVNAUCalxKSAIbAwULCQgHAgYV
            CgkICwIEFgIDAQIeAQIXgAAKCRBteKvz2ReVNAUoAQCi9uhyZCB8aY/iupXHv0j9
            3HOkEbVmB1B/xRn+xdcu4gEAn2JbiIts/RVYYk8RXwTVp3zrksdrTZ1zBiBUC/ZH
            TQ8=
            =+uqe
            -----END PGP PUBLIC KEY BLOCK-----
        """.trimIndent()
    }
}
