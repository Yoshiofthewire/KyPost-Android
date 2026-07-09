package com.urlxl.mail.contacts

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.urlxl.mail.ScopedValue

private val Context.contactsDataStore by preferencesDataStore(name = "contacts_state")

/**
 * Durable per-subscriber sync cursor, mirroring [com.urlxl.mail.push.PushRepository]'s pull-cursor
 * pattern exactly: scoped to the subscriber so re-pairing as someone else starts clean.
 */
class ContactCursorStore(context: Context) {
    private val cursor = ScopedValue(
        dataStore = context.contactsDataStore,
        scopeKey = stringPreferencesKey("contacts_cursor_sub"),
        valueKey = longPreferencesKey("contacts_cursor"),
    )

    suspend fun cursor(subscriberId: String): Long = cursor.get(subscriberId) ?: 0L

    suspend fun advanceCursor(subscriberId: String, newCursor: Long) {
        cursor.update(subscriberId) { current -> maxOf(current ?: 0L, newCursor) }
    }

    /** Used for tooOld handling: discard the cursor so the next sync does a full since=0 pull. */
    suspend fun resetCursor(subscriberId: String) {
        cursor.set(subscriberId, 0L)
    }
}
