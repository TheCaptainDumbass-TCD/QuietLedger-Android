package com.quietwire.quietledger.ui.security

import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What About reports about the schema.
 *
 * The version was a `buildConfigField` literal that said 6 while the database
 * was on 8. A screen whose purpose is to state what is running cannot carry a
 * number someone has to remember to update, so it now reads the open database
 * and compares against what the build expects.
 */
class AboutStateTest {

    @Test
    fun `a matching version is not reported as a mismatch`() {
        val state = AboutUiState(
            databaseOpen = true,
            schemaVersion = QuietLedgerDatabase.VERSION
        )

        assertFalse(state.schemaMismatch)
    }

    @Test
    fun `a database behind the build is reported`() {
        // Means a migration did not run, which is worth seeing rather than
        // discovering through a missing column.
        val state = AboutUiState(
            databaseOpen = true,
            schemaVersion = QuietLedgerDatabase.VERSION - 1
        )

        assertTrue(state.schemaMismatch)
    }

    @Test
    fun `nothing is claimed while the database is closed`() {
        // Zero is not a version. Comparing it would report a mismatch on every
        // locked launch.
        val state = AboutUiState(databaseOpen = false, schemaVersion = 0)

        assertFalse(state.schemaMismatch)
    }

    @Test
    fun `the expected version comes from the database class itself`() {
        // Not from a second copy of the number, which is how this broke.
        assertEquals(QuietLedgerDatabase.VERSION, AboutUiState().expectedSchemaVersion)
    }
}
