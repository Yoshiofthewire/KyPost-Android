package com.urlxl.mail.pgp

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import java.io.ByteArrayInputStream

/**
 * Computes an OpenPGP key's fingerprint from the key's own bytes, rather than trusting whatever
 * fingerprint string a server response claims alongside it. A compromised/malicious server (or a
 * MITM on an http fallback) could otherwise send an armored key paired with an unrelated
 * fingerprint string, and the app would have no way to notice the two don't match — the user's
 * out-of-band "does this fingerprint match?" check would be verifying a label with no
 * cryptographic relationship to what actually gets saved. Parsing the key locally and hashing what
 * it actually contains closes that gap.
 */
object PgpFingerprint {

    /** Returns the primary key's fingerprint as space-grouped uppercase hex (comparable to what
     *  `gpg --fingerprint` or any other PGP client shows), or null if [armoredPublicKey] isn't a
     *  parseable OpenPGP public key. Callers must treat null as "reject this key" — never fall back
     *  to displaying a server-supplied fingerprint string instead. */
    fun compute(armoredPublicKey: String): String? = runCatching {
        val decoder = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredPublicKey.toByteArray(Charsets.UTF_8)))
        val ring = JcaPGPObjectFactory(decoder).nextObject() as? PGPPublicKeyRing ?: return@runCatching null
        format(ring.publicKey.fingerprint)
    }.getOrNull()

    private fun format(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
}
