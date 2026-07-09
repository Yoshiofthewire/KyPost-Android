package com.urlxl.mail.mail

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.urlxl.mail.ScopedValue
import kotlinx.coroutines.runBlocking

private val Context.mailSyncDataStore by preferencesDataStore(name = "mail_sync_state")

private const val FULL_RESYNC_INTERVAL_MS = 24L * 60 * 60 * 1000

/**
 * Blocking (non-suspend) by design, matching [MailSource] and [RelayMailSource]'s own
 * synchronous style — callers already run on a background executor thread. Backed by
 * [MailCursorStore] in production; tests inject an in-memory fake instead.
 */
interface MailCursorProvider {
    /** Null means "no cursor yet for this subscriber+folder" — caller should send since=0. */
    fun cursor(subscriberId: String, folder: String): String?
    fun saveCursor(subscriberId: String, folder: String, cursor: String)
    /** True once a day (per subscriber+folder) or if a full resync has never been recorded —
     *  the documented self-heal for a missed removal notification (Mobile_Mail_Relay.md Part 5). */
    fun shouldForceFullResync(subscriberId: String, folder: String): Boolean
    fun recordFullResync(subscriberId: String, folder: String)
}

/**
 * Durable per-subscriber, per-folder delta-sync cursor for GET /api/inbox (Mobile_Mail_Relay.md
 * Part 5, v2), mirroring [com.urlxl.mail.push.PushRepository]'s pull-cursor pattern exactly.
 * Scoped to subscriber+folder so re-pairing or switching mailboxes can't apply a stale/foreign
 * cursor. Cursors are opaque server-issued strings, not assumed to be numeric or ordered.
 */
class MailCursorStore(
    private val context: Context,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : MailCursorProvider {

    override fun cursor(subscriberId: String, folder: String): String? = runBlocking {
        cursorValue(folder).get(subscriberId)?.takeIf { it.isNotBlank() }
    }

    override fun saveCursor(subscriberId: String, folder: String, cursor: String) {
        if (cursor.isBlank()) return
        runBlocking { cursorValue(folder).set(subscriberId, cursor) }
    }

    override fun shouldForceFullResync(subscriberId: String, folder: String): Boolean = runBlocking {
        val lastAt = resyncValue(folder).get(subscriberId)
        lastAt == null || (nowProvider() - lastAt) >= FULL_RESYNC_INTERVAL_MS
    }

    override fun recordFullResync(subscriberId: String, folder: String) {
        runBlocking { resyncValue(folder).set(subscriberId, nowProvider()) }
    }

    private fun cursorValue(folder: String) = ScopedValue(
        dataStore = context.mailSyncDataStore,
        scopeKey = stringPreferencesKey("inbox_cursor_sub_$folder"),
        valueKey = stringPreferencesKey("inbox_cursor_$folder"),
    )

    private fun resyncValue(folder: String) = ScopedValue(
        dataStore = context.mailSyncDataStore,
        scopeKey = stringPreferencesKey("inbox_cursor_sub_$folder"),
        valueKey = longPreferencesKey("inbox_last_full_resync_$folder"),
    )
}
