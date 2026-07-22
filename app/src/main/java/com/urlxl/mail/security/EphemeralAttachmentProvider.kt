package com.urlxl.mail.security

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val AUTHORITY_SUFFIX = ".ephemeralattachments"

internal data class PendingAttachment(val bytes: ByteArray, val mimeType: String)

/**
 * In-memory holder for attachment bytes awaiting a single ephemeral read, keyed by a one-time
 * token. Nothing here is ever written to disk — see [EphemeralAttachmentProvider], the
 * `ContentProvider` that actually serves these bytes to a viewer app.
 */
object EphemeralAttachmentBytes {
    private val pending = ConcurrentHashMap<String, PendingAttachment>()
    private var authority: String = ""

    internal fun configure(authority: String) {
        this.authority = authority
    }

    fun register(bytes: ByteArray, mimeType: String): Uri {
        val token = UUID.randomUUID().toString()
        pending[token] = PendingAttachment(bytes, mimeType)
        return Uri.parse("content://$authority/$token")
    }

    internal fun take(token: String): PendingAttachment? = pending.remove(token)

    internal fun peekMimeType(token: String): String? = pending[token]?.mimeType
}

/**
 * Serves attachment bytes registered via [EphemeralAttachmentBytes.register] through a pipe,
 * never a file — see "Attachments" under Hostile Location Protection in the 2026-07-22
 * security-hardening spec. Each token is single-use: [EphemeralAttachmentBytes.take] removes it
 * from memory the moment this provider starts serving it.
 */
class EphemeralAttachmentProvider : ContentProvider() {
    override fun attachInfo(context: android.content.Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        EphemeralAttachmentBytes.configure(info.authority)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = EphemeralAttachmentBytes.peekMimeType(tokenFrom(uri))

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val attachment = EphemeralAttachmentBytes.take(tokenFrom(uri))
            ?: throw IOException("Attachment already consumed or unknown: $uri")
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(attachment.bytes) }
        }.start()
        return readSide
    }

    private fun tokenFrom(uri: Uri): String = uri.lastPathSegment.orEmpty()

    // Not a real data table — attachments are single-use byte streams, not queryable rows.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
