package com.quietwire.quietledger.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the migrations against real SQLite, with Room validating the resulting
 * schema against the exported JSON.
 *
 * Instrumented rather than a JVM test because `MigrationTestHelper` loads the
 * exported schemas from Android assets, which are not merged for unit tests.
 * `SchemaChangeTest` covers what can be checked without a device, comparing the
 * committed schemas for dropped columns and missing versions, and runs on every
 * build. This covers the part that needs an actual database: that the migration
 * executes, preserves rows, and leaves a schema Room will accept.
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    private val databaseName = "migration-instrumented.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        QuietLedgerDatabase::class.java
    )

    @Test
    fun version1DataSurvivesTheMoveToVersion2() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                """
                INSERT INTO debt (
                    name, category, current_balance_minor, original_balance_minor,
                    minimum_payment_minor, scheduled_payment_minor, payment_frequency,
                    apr_basis_points, due_day_of_month, closed_at, notes, sort_order,
                    created_at, updated_at
                ) VALUES (
                    'Northbridge CU', 'CREDIT_CARD', 998597, 998597, 3900, 12500, 'WEEKLY',
                    1199, NULL, NULL, 'imported', 0, 1000, 1000
                )
                """.trimIndent()
            )
            close()
        }

        // validateDroppedTables = true, so a migration that rebuilds a table and
        // leaves the original behind is caught rather than tolerated.
        val migrated = helper.runMigrationsAndValidate(
            databaseName, 2, true, *QuietLedgerDatabase.ALL_MIGRATIONS
        )

        migrated.query(
            "SELECT name, current_balance_minor, scheduled_payment_minor, " +
                "payment_frequency, apr_basis_points, apr_is_promotional FROM debt"
        ).use { cursor ->
            assertEquals("the row must survive", 1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Northbridge CU", cursor.getString(0))
            assertEquals(998_597L, cursor.getLong(1))
            assertEquals(12_500L, cursor.getLong(2))
            assertEquals("WEEKLY", cursor.getString(3))
            assertEquals(1199, cursor.getInt(4))
            // Defaults to 0, so an existing row reads as "not promotional"
            // rather than unknown.
            assertEquals(0, cursor.getInt(5))
        }
        migrated.close()
    }
}
