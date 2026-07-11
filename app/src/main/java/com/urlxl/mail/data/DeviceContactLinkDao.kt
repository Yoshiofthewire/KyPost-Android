package com.urlxl.mail.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface DeviceContactLinkDao {
    @Query("SELECT * FROM device_contact_links")
    suspend fun getAll(): List<DeviceContactLinkEntity>

    @Query("SELECT * FROM device_contact_links WHERE uid = :uid")
    suspend fun getByUid(uid: String): DeviceContactLinkEntity?

    @Query("SELECT * FROM device_contact_links WHERE rawContactId = :rawContactId")
    suspend fun getByRawContactId(rawContactId: Long): DeviceContactLinkEntity?

    @Upsert
    suspend fun upsert(link: DeviceContactLinkEntity)

    @Query("DELETE FROM device_contact_links WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)
}
