package com.urlxl.mail.push

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val ENCRYPTED_PREFS_FILE_NAME = "push_pairing_secure"

private const val KEY_SUBSCRIBER_ID = "pair_sub"
private const val KEY_DEVICE_SECRET = "pair_device_secret"
private const val KEY_SERVER_URL = "pair_srv"
private const val KEY_REGISTRATION_URL = "pair_reg"
private const val KEY_PAIRING_TOKEN = "pair_pt"
private const val KEY_DEVICE_ID = "pair_device_id"
private const val KEY_PAIRED_AT = "pair_paired_at"

/**
 * Holds pairing proof material (device secret, pairing token) in a Keystore-backed
 * EncryptedSharedPreferences file rather than the plaintext DataStore used for the rest
 * of the push state (history, sync status, server URL setting).
 */
class SecurePairingStore(context: Context) {
    private val prefs: SharedPreferences by lazy { buildEncryptedPrefs(context.applicationContext) }

    private val _pairing = MutableStateFlow<PairingData?>(null)
    val pairing: StateFlow<PairingData?> = _pairing.asStateFlow()

    init {
        _pairing.value = readPairing()
    }

    suspend fun savePairing(pairing: PairingData) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_SUBSCRIBER_ID, pairing.subscriberId)
                .putString(KEY_SERVER_URL, pairing.serverUrl)
                .putString(KEY_REGISTRATION_URL, pairing.registrationUrl)
                .putString(KEY_PAIRING_TOKEN, pairing.pairingToken)
                .apply {
                    if (pairing.deviceId.isNullOrBlank()) remove(KEY_DEVICE_ID) else putString(KEY_DEVICE_ID, pairing.deviceId)
                    if (pairing.deviceSecret.isNullOrBlank()) remove(KEY_DEVICE_SECRET) else putString(KEY_DEVICE_SECRET, pairing.deviceSecret)
                }
                .putLong(KEY_PAIRED_AT, pairing.pairedAtEpochMs)
                .commit()
        }
        _pairing.value = readPairing()
    }

    suspend fun clearPairing() {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .remove(KEY_SUBSCRIBER_ID)
                .remove(KEY_DEVICE_SECRET)
                .remove(KEY_SERVER_URL)
                .remove(KEY_REGISTRATION_URL)
                .remove(KEY_PAIRING_TOKEN)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_PAIRED_AT)
                .commit()
        }
        _pairing.value = null
    }

    private fun readPairing(): PairingData? {
        val subId = prefs.getString(KEY_SUBSCRIBER_ID, null).orEmpty()
        val serverUrl = prefs.getString(KEY_SERVER_URL, null).orEmpty()
        val registrationUrl = prefs.getString(KEY_REGISTRATION_URL, null).orEmpty()
        val pairingToken = prefs.getString(KEY_PAIRING_TOKEN, null).orEmpty()
        val pairedAt = if (prefs.contains(KEY_PAIRED_AT)) prefs.getLong(KEY_PAIRED_AT, 0L) else null

        if (subId.isBlank() || serverUrl.isBlank() ||
            registrationUrl.isBlank() || pairingToken.isBlank() || pairedAt == null
        ) {
            return null
        }

        return PairingData(
            subscriberId = subId,
            serverUrl = serverUrl,
            registrationUrl = registrationUrl,
            pairingToken = pairingToken,
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            deviceSecret = prefs.getString(KEY_DEVICE_SECRET, null),
            pairedAtEpochMs = pairedAt,
        )
    }

    private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
        return try {
            createEncryptedPrefs(appContext)
        } catch (e: Exception) {
            // The Keystore-backed key can become unable to decrypt the stored keyset (e.g. OS-level
            // key invalidation) — that's unrecoverable, and it happens in the init path, so an
            // uncaught failure here crashes the app on every launch. Reset to a fresh, empty
            // encrypted file instead; readPairing() then reports null and the user just re-pairs.
            android.util.Log.e("SecurePairingStore", "Encrypted pairing store unreadable, resetting", e)
            appContext.deleteSharedPreferences(ENCRYPTED_PREFS_FILE_NAME)
            createEncryptedPrefs(appContext)
        }
    }

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
