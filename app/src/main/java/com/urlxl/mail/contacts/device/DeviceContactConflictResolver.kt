package com.urlxl.mail.contacts.device

import java.time.Instant

enum class Winner {
    ROOM, DEVICE, TIE_PREFER_ROOM
}

object DeviceContactConflictResolver {
    fun parseIso(isoString: String?): Long? {
        if (isoString.isNullOrEmpty()) return null
        return runCatching {
            Instant.parse(isoString).toEpochMilli()
        }.getOrNull()
    }

    fun resolve(
        roomUpdatedAtEpochMs: Long?,
        deviceUpdatedAtEpochMs: Long?,
    ): Winner {
        return when {
            roomUpdatedAtEpochMs == null && deviceUpdatedAtEpochMs == null -> TIE_PREFER_ROOM
            roomUpdatedAtEpochMs == null -> DEVICE
            deviceUpdatedAtEpochMs == null -> ROOM
            roomUpdatedAtEpochMs > deviceUpdatedAtEpochMs -> ROOM
            deviceUpdatedAtEpochMs > roomUpdatedAtEpochMs -> DEVICE
            else -> TIE_PREFER_ROOM
        }
    }
}
