package com.urlxl.mail.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MIGRATION_3_4 (Task 2 extended contact fields) applies cleanly against a real
 * version-3 `contacts` table, matching the instrumentation-test convention documented in
 * app/src/androidTest/AGENTS.md (MigrationTestHelper needs Android's real SQLite, which JVM
 * unit tests under app/src/test can't provide).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate3To4_addsExtendedContactColumns_andPreservesExistingRow() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO contacts (uid, rev, fn, emailsJson, phonesJson, addressesJson) " +
                    "VALUES ('uid-1', 1, 'Ada Lovelace', '[]', '[]', '[]')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

        migrated.query("SELECT * FROM contacts WHERE uid = 'uid-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Ada Lovelace", cursor.getString(cursor.getColumnIndexOrThrow("fn")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("groupIDsJson")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("pgpKey")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("imsJson")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("websitesJson")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("relationsJson")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("eventsJson")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("phoneticGivenName")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("phoneticFamilyName")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("department")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("customFieldsJson")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("pronouns")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("photoRef")))
        }
    }

    @Test
    fun migrate4To5_createsGroupTables_andPreservesExistingContactRow() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO contacts (uid, rev, fn, emailsJson, phonesJson, addressesJson) " +
                    "VALUES ('uid-1', 1, 'Ada Lovelace', '[]', '[]', '[]')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5)

        migrated.query("SELECT * FROM contacts WHERE uid = 'uid-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Ada Lovelace", cursor.getString(cursor.getColumnIndexOrThrow("fn")))
        }

        migrated.execSQL("INSERT INTO `groups` (id, name, rev) VALUES ('group-1', 'Work', 3)")
        migrated.query("SELECT * FROM `groups` WHERE id = 'group-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Work", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals(3L, cursor.getLong(cursor.getColumnIndexOrThrow("rev")))
        }

        migrated.execSQL("INSERT INTO `group_links` (groupId, androidGroupRowId) VALUES ('group-1', 42)")
        migrated.query("SELECT * FROM `group_links` WHERE groupId = 'group-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("androidGroupRowId")))
        }
    }

    @Test
    fun migrate5To6_addsIsSelfColumn_andPreservesExistingRow() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO contacts (uid, rev, fn, emailsJson, phonesJson, addressesJson) " +
                    "VALUES ('uid-1', 1, 'Ada Lovelace', '[]', '[]', '[]')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)

        migrated.query("SELECT * FROM contacts WHERE uid = 'uid-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Ada Lovelace", cursor.getString(cursor.getColumnIndexOrThrow("fn")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isSelf")))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
