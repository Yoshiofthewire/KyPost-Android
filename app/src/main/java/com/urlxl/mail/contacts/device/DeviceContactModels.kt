package com.urlxl.mail.contacts.device

import com.urlxl.mail.contacts.ContactAddressDto
import com.urlxl.mail.contacts.ContactFieldDto

data class DeviceRawContactSnapshot(
    val rawContactId: Long,
    val contactId: Long,
    val accountType: String?,
    val accountName: String?,
    val lastUpdatedEpochMs: Long,
    val dirty: Boolean,
    val fn: String,
    val org: String?,
    val notes: String?,
    val birthday: String?,
    val emails: List<ContactFieldDto>,
    val phones: List<ContactFieldDto>,
    val addresses: List<ContactAddressDto>,
)

data class DeviceContactCandidate(
    val contactId: Long,
    val rawContactId: Long,
    val lastUpdatedEpochMs: Long,
    val emails: List<String>,
    val phones: List<String>,
    val fn: String,
    val org: String?,
    val notes: String?,
)

data class DeviceFieldSet(
    val fn: String,
    val givenName: String?,
    val familyName: String?,
    val middleName: String?,
    val prefix: String?,
    val suffix: String?,
    val nickname: String?,
    val org: String?,
    val title: String?,
    val notes: String?,
    val birthday: String?,
    val emails: List<ContactFieldDto>,
    val phones: List<ContactFieldDto>,
    val addresses: List<ContactAddressDto>,
)
