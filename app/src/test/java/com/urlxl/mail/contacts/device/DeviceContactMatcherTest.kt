package com.urlxl.mail.contacts.device

import com.urlxl.mail.contacts.ContactDto
import com.urlxl.mail.contacts.ContactFieldDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceContactMatcherTest {
    @Test
    fun normalizeEmailTrimsAndLowercases() {
        assertEquals("test@example.com", DeviceContactMatcher.normalizeEmail("  TEST@EXAMPLE.COM  "))
    }

    @Test
    fun normalizePhoneStripsNonDigits() {
        assertEquals("5551234567", DeviceContactMatcher.normalizePhone("+1 (555) 123-4567"))
        assertEquals("5551234567", DeviceContactMatcher.normalizePhone("555.123.4567"))
    }

    @Test
    fun findMatchByEmail() {
        val existing = listOf(
            ContactDto(uid = "uid1", fn = "Alice", emails = listOf(ContactFieldDto(value = "alice@example.com"))),
        )
        val candidate = listOf("alice@EXAMPLE.COM")

        assertEquals("uid1", DeviceContactMatcher.findMatch(candidate, emptyList(), existing))
    }

    @Test
    fun findMatchByPhone() {
        val existing = listOf(
            ContactDto(uid = "uid2", fn = "Bob", phones = listOf(ContactFieldDto(value = "+1-555-987-6543"))),
        )
        val candidate = listOf("555-987-6543")

        assertEquals("uid2", DeviceContactMatcher.findMatch(emptyList(), candidate, existing))
    }

    @Test
    fun findMatchNoMatch() {
        val existing = listOf(
            ContactDto(uid = "uid3", fn = "Charlie", emails = listOf(ContactFieldDto(value = "charlie@example.com"))),
        )
        val candidate = listOf("notfound@example.com")

        assertNull(DeviceContactMatcher.findMatch(candidate, emptyList(), existing))
    }

    @Test
    fun findMatchEmptyExisting() {
        val existing = emptyList<ContactDto>()
        val candidate = listOf("test@example.com")

        assertNull(DeviceContactMatcher.findMatch(candidate, emptyList(), existing))
    }

    @Test
    fun findMatchEmptyCandidate() {
        val existing = listOf(
            ContactDto(uid = "uid4", fn = "David", emails = listOf(ContactFieldDto(value = "david@example.com"))),
        )

        assertNull(DeviceContactMatcher.findMatch(emptyList(), emptyList(), existing))
    }
}
