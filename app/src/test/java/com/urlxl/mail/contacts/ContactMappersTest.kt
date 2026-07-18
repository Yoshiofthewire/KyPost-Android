package com.urlxl.mail.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactMappersTest {

    @Test
    fun toEntity_toDto_roundTripsAllExtendedFields() {
        val dto = ContactDto(
            uid = "uid-1",
            rev = 1,
            fn = "Ada Lovelace",
            groupIDs = listOf("group-1", "group-2"),
            photoRef = "photo-ref-1",
            pgpKey = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nabc\n-----END PGP PUBLIC KEY BLOCK-----",
            ims = listOf(ContactImDto(service = "signal", label = "Home", value = "ada.lovelace")),
            websites = listOf(ContactUrlDto(label = "Blog", value = "https://example.com")),
            relations = listOf(ContactRelationDto(label = "Spouse", name = "William King")),
            events = listOf(ContactEventDto(label = "Anniversary", date = "1835-07-08")),
            phoneticGivenName = "AY-dah",
            phoneticFamilyName = "LUV-lace",
            department = "Analytical Engines",
            customFields = listOf(ContactCustomFieldDto(label = "Employee ID", value = "42")),
            pronouns = "she/her",
        )

        val entity = dto.toEntity()
        val roundTripped = entity.toDto()

        assertEquals(dto.groupIDs, roundTripped.groupIDs)
        assertEquals(dto.photoRef, roundTripped.photoRef)
        assertEquals(dto.pgpKey, roundTripped.pgpKey)
        assertEquals(dto.ims, roundTripped.ims)
        assertEquals(dto.websites, roundTripped.websites)
        assertEquals(dto.relations, roundTripped.relations)
        assertEquals(dto.events, roundTripped.events)
        assertEquals(dto.phoneticGivenName, roundTripped.phoneticGivenName)
        assertEquals(dto.phoneticFamilyName, roundTripped.phoneticFamilyName)
        assertEquals(dto.department, roundTripped.department)
        assertEquals(dto.customFields, roundTripped.customFields)
        assertEquals(dto.pronouns, roundTripped.pronouns)
    }

    @Test
    fun toEntity_defaultExtendedFields_encodeAsEmptyJsonArraysAndNulls() {
        val dto = ContactDto(uid = "uid-2", fn = "No Extras")

        val entity = dto.toEntity()

        assertEquals("[]", entity.groupIDsJson)
        assertEquals(null, entity.photoRef)
        assertEquals(null, entity.pgpKey)
        assertEquals("[]", entity.imsJson)
        assertEquals("[]", entity.websitesJson)
        assertEquals("[]", entity.relationsJson)
        assertEquals("[]", entity.eventsJson)
        assertEquals(null, entity.phoneticGivenName)
        assertEquals(null, entity.phoneticFamilyName)
        assertEquals(null, entity.department)
        assertEquals("[]", entity.customFieldsJson)
        assertEquals(null, entity.pronouns)
    }

    @Test
    fun toDto_blankJsonColumns_decodeAsEmptyLists() {
        val entity = ContactDto(uid = "uid-3", fn = "Legacy Row").toEntity().copy(
            groupIDsJson = "not json",
            imsJson = "not json",
            websitesJson = "not json",
            relationsJson = "not json",
            eventsJson = "not json",
            customFieldsJson = "not json",
        )

        val dto = entity.toDto()

        assertEquals(emptyList<String>(), dto.groupIDs)
        assertEquals(emptyList<ContactImDto>(), dto.ims)
        assertEquals(emptyList<ContactUrlDto>(), dto.websites)
        assertEquals(emptyList<ContactRelationDto>(), dto.relations)
        assertEquals(emptyList<ContactEventDto>(), dto.events)
        assertEquals(emptyList<ContactCustomFieldDto>(), dto.customFields)
    }

    @Test
    fun toEntity_toDto_roundTripsIsSelf() {
        val selfDto = ContactDto(uid = "uid-self", fn = "Me", isSelf = true)
        val otherDto = ContactDto(uid = "uid-other", fn = "Not Me", isSelf = false)

        assertEquals(true, selfDto.toEntity().toDto().isSelf)
        assertEquals(true, selfDto.toEntity().isSelf)
        assertEquals(false, otherDto.toEntity().toDto().isSelf)
        assertEquals(false, otherDto.toEntity().isSelf)
    }
}
