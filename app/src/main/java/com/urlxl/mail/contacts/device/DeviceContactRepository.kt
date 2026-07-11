package com.urlxl.mail.contacts.device

import android.content.Context
import android.provider.ContactsContract
import com.urlxl.mail.contacts.ContactSyncRepository
import com.urlxl.mail.data.AppDatabase

class DeviceContactRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val syncRepository: ContactSyncRepository,
) {
    private val contentResolver = context.contentResolver

    suspend fun syncAll() {
        pullDeviceChangesForOwnAccount()
        importNewDeviceContacts()
        pushRoomChangesToDevice()
    }

    private suspend fun pullDeviceChangesForOwnAccount() {
    }

    private suspend fun importNewDeviceContacts() {
    }

    private suspend fun pushRoomChangesToDevice() {
    }

    suspend fun deleteDeviceRawContact(uid: String) {
    }
}
