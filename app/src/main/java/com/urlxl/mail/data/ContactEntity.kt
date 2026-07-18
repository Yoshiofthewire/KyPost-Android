package com.urlxl.mail.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mirrors the `Contact` JSON shape in Mobile_Contact_Sync.md. [emailsJson]/[phonesJson]/
 * [addressesJson] hold pre-encoded kotlinx.serialization JSON for the field-entry lists — plain
 * String columns rather than a TypeConverter, since callers already have a Json instance handy
 * from decoding the sync response. The newer list columns ([groupIDsJson], [imsJson],
 * [websitesJson], [relationsJson], [eventsJson], [customFieldsJson]) carry an explicit
 * `@ColumnInfo(defaultValue = "[]")` — unlike the original three, they were added to an
 * already-populated table via [AppDatabase.MIGRATION_3_4], and SQLite requires a NOT NULL
 * column added by ALTER TABLE to declare a default so existing rows stay valid.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val uid: String,
    val rev: Long,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val fn: String,
    val givenName: String? = null,
    val familyName: String? = null,
    val middleName: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val nickname: String? = null,
    val org: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val birthday: String? = null,
    val emailsJson: String = "[]",
    val phonesJson: String = "[]",
    val addressesJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val groupIDsJson: String = "[]",
    val photoRef: String? = null,
    val pgpKey: String? = null,
    @ColumnInfo(defaultValue = "[]") val imsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val websitesJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val relationsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val eventsJson: String = "[]",
    val phoneticGivenName: String? = null,
    val phoneticFamilyName: String? = null,
    val department: String? = null,
    @ColumnInfo(defaultValue = "[]") val customFieldsJson: String = "[]",
    val pronouns: String? = null,
    @ColumnInfo(defaultValue = "0") val isSelf: Boolean = false,
)
