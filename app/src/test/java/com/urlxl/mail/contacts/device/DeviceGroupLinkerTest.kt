package com.urlxl.mail.contacts.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers [findExistingGroupRowId], the pure find-by-title decision [DeviceGroupLinker] uses to
 *  avoid duplicating an on-device group the user already has — the class itself needs a real
 *  `ContentResolver`/`AppDatabase` so isn't directly unit-testable in this repo's no-Robolectric
 *  JVM test setup (same gap `ContactSyncRepositoryTest.kt` documents for `ContactSyncRepository`). */
class DeviceGroupLinkerTest {
    @Test
    fun findExistingGroupRowId_noGroups_returnsNull() {
        assertNull(findExistingGroupRowId(emptyList(), "Work"))
    }

    @Test
    fun findExistingGroupRowId_matchingTitle_returnsItsRowId() {
        val existing = listOf(1L to "Family", 2L to "Work")
        assertEquals(2L, findExistingGroupRowId(existing, "Work"))
    }

    @Test
    fun findExistingGroupRowId_noMatchingTitle_returnsNull() {
        val existing = listOf(1L to "Family", 2L to "Work")
        assertNull(findExistingGroupRowId(existing, "Friends"))
    }

    @Test
    fun findExistingGroupRowId_isCaseSensitive() {
        val existing = listOf(1L to "work")
        assertNull(findExistingGroupRowId(existing, "Work"))
    }

    @Test
    fun findExistingGroupRowId_firstMatchWins() {
        val existing = listOf(1L to "Work", 2L to "Work")
        assertEquals(1L, findExistingGroupRowId(existing, "Work"))
    }
}
