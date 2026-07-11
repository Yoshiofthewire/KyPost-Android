package com.urlxl.mail.contacts.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceContactFieldMergeTest {
    @Test
    fun mergeStringBothEmpty() {
        assertNull(DeviceContactFieldMerge.mergeStringField(null, null, null, null))
        assertNull(DeviceContactFieldMerge.mergeStringField("", "", null, null))
    }

    @Test
    fun mergeStringRoomEmptyDevicePopulated() {
        assertEquals("device value", DeviceContactFieldMerge.mergeStringField(null, "device value", null, 100L))
        assertEquals("device value", DeviceContactFieldMerge.mergeStringField("", "device value", null, 100L))
    }

    @Test
    fun mergeStringRoomPopulatedDeviceEmpty() {
        assertEquals("room value", DeviceContactFieldMerge.mergeStringField("room value", null, 100L, null))
        assertEquals("room value", DeviceContactFieldMerge.mergeStringField("room value", "", 100L, null))
    }

    @Test
    fun mergeStringBothPopulatedRoomNewer() {
        assertEquals("room value", DeviceContactFieldMerge.mergeStringField("room value", "device value", 200L, 100L))
    }

    @Test
    fun mergeStringBothPopulatedDeviceNewer() {
        assertEquals("device value", DeviceContactFieldMerge.mergeStringField("room value", "device value", 100L, 200L))
    }

    @Test
    fun mergeStringBothPopulatedTieThenRoomWins() {
        assertEquals("room value", DeviceContactFieldMerge.mergeStringField("room value", "device value", 100L, 100L))
    }

    @Test
    fun mergeEmailListBothEmpty() {
        assertEquals(emptyList<com.urlxl.mail.contacts.ContactFieldDto>(), DeviceContactFieldMerge.mergeEmailList(emptyList(), emptyList(), null, null))
    }

    @Test
    fun mergeEmailListRoomEmptyDevicePopulated() {
        val deviceEmails = listOf(com.urlxl.mail.contacts.ContactFieldDto(value = "test@example.com"))
        assertEquals(deviceEmails, DeviceContactFieldMerge.mergeEmailList(emptyList(), deviceEmails, null, 100L))
    }

    @Test
    fun mergeEmailListRoomPopulatedDeviceEmpty() {
        val roomEmails = listOf(com.urlxl.mail.contacts.ContactFieldDto(value = "test@example.com"))
        assertEquals(roomEmails, DeviceContactFieldMerge.mergeEmailList(roomEmails, emptyList(), 100L, null))
    }

    @Test
    fun mergeEmailListBothPopulatedRoomNewer() {
        val roomEmails = listOf(com.urlxl.mail.contacts.ContactFieldDto(value = "room@example.com"))
        val deviceEmails = listOf(com.urlxl.mail.contacts.ContactFieldDto(value = "device@example.com"))
        assertEquals(roomEmails, DeviceContactFieldMerge.mergeEmailList(roomEmails, deviceEmails, 200L, 100L))
    }

    @Test
    fun mergeEmailListBothPopulatedDeviceNewer() {
        val roomEmails = listOf(com.urlxl.mail.contacts.ContactFieldDto(value = "room@example.com"))
        val deviceEmails = listOf(com.urlxl.mail.contacts.ContactFieldDto(value = "device@example.com"))
        assertEquals(deviceEmails, DeviceContactFieldMerge.mergeEmailList(roomEmails, deviceEmails, 100L, 200L))
    }
}
