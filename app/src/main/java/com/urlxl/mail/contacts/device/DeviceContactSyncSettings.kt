package com.urlxl.mail.contacts.device

import android.content.Context
import android.content.SharedPreferences

class DeviceContactSyncSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastForeignScanAtEpochMs(): Long = prefs.getLong(KEY_LAST_FOREIGN_SCAN, 0L)

    fun setLastForeignScanAtEpochMs(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_FOREIGN_SCAN, epochMs).apply()
    }

    companion object {
        private const val PREFS_NAME = "com.urlxl.mail.device_contacts"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_FOREIGN_SCAN = "last_foreign_scan_epoch_ms"
    }
}
