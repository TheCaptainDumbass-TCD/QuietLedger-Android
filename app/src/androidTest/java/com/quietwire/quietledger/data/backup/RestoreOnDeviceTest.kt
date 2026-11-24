package com.quietwire.quietledger.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Restores a real backup, on the real device, without touching the real data.
 *
 * This is the test that was missing, and the gap behind it was worse than a
 * missing test. The phone seals every backup to the server's public key and
 * cannot read what it writes, so every backup being produced was unrestorable:
 * the app's import path accepts a passphrase only, and nothing existed to turn
 * a sealed file back into one. That would have been discovered during an actual
 * recovery.
 *
 * The server now unseals a backup and re-encrypts it under a passphrase. This
 * proves the phone can import the result.
 *
 * Isolated deliberately. It builds its own unencrypted database at a temporary
 * path, so the live ledger is never opened, never written and never at risk.
 * The only thing shared with the real app is the code under test. There is one
 * Android device and its data is real, so a destructive test is not an option.
 *
 * The fixture was produced by the server from an actual sealed backup off this
 * phone. Generating it inside the test would prove only that Kotlin agrees
 * with itself. Because it holds real data, the fixture is written into the test
 * assets by `scripts/verify-restore.sh` for each run and is never committed.
 */
@RunWith(AndroidJUnit4::class)
class RestoreOnDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The test APK's own assets, where the fixture lives.
     *
     * `context.assets` is the app under test and does not contain it. Using the
     * wrong one threw FileNotFoundException, and the wrong-passphrase test
     * below caught it and passed, because it was catching Throwable. A missing
     * fixture counted as a correct refusal.
     */
    private val fixtures
        get() = InstrumentationRegistry.getInstrumentation().context.assets
    /**
     * Passed in by `scripts/verify-restore.sh`, which generates it.
     *
     * Not baked in, because the passphrase is new for every restore file. A
     * hardcoded one would make this test pass only against one stale fixture.
     */
    private val passphrase: String
        get() = InstrumentationRegistry.getArguments()
            .getString("restorePassphrase")
            ?: error(
                "No restorePassphrase given. Run scripts/verify-restore.sh " +
                    "rather than this test on its own."
            )

    /**
     * The figures the restore must reproduce, passed in by
     * `scripts/verify-restore.sh` from `~/.config/quietledger/restore-expect`.
     *
     * Kept out of the repository because they are the real figures on this
     * phone. Compared exactly, so a table dropped on the way through fails
     * here rather than merely importing something.
     */
    private fun expected(name: String): Long =
        InstrumentationRegistry.getArguments().getString(name)?.toLongOrNull()
            ?: error(
                "No $name given. Run scripts/verify-restore.sh " +
                    "rather than this test on its own."
            )

    private lateinit var database: QuietLedgerDatabase
    private lateinit var databaseFile: File

    private fun freshDatabase(): QuietLedgerDatabase {
        databaseFile = File(context.cacheDir, "restore-test-${System.nanoTime()}.db")

        // Unencrypted and temporary on purpose. What is under test is the
        // import logic, and a throwaway file keeps this well away from the real
        // database and its Keystore-wrapped key.
        return Room.databaseBuilder(
            context,
            QuietLedgerDatabase::class.java,
            databaseFile.path
        ).build()
    }

    private fun repositoryFor(db: QuietLedgerDatabase) = BackupRepository(
        object : DatabaseHolder {
            override fun require() = db
        },
        "restore-test"
    )

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::databaseFile.isInitialized) {
            databaseFile.delete()
            File("${databaseFile.path}-shm").delete()
            File("${databaseFile.path}-wal").delete()
        }
    }

    @Test
    fun restoresEverythingFromTheServersRestoreFile() = runBlocking {
        database = freshDatabase()
        val repository = repositoryFor(database)

        assertEquals("the test database must start empty", 0, database.debtDao().getAll().size)

        val summary = fixtures.open("restore-from-server.qlbk").use { input ->
            repository.import(input, passphrase.toCharArray())
        }
        assertTrue("import reported nothing restored", summary.total > 0)

        assertEquals(expected("expectDebts"), database.debtDao().getAll().size.toLong())
        assertEquals(
            expected("expectRecurringExpenses"),
            database.recurringExpenseDao().getAllIncludingInactive().size.toLong()
        )
        assertEquals(expected("expectSavingsAccounts"), database.savingsAccountDao().getAll().size.toLong())
        assertEquals(expected("expectHoldings"), database.holdingDao().getAll().size.toLong())
        assertEquals(expected("expectMetalLots"), database.metalLotDao().getAll().size.toLong())
        assertEquals(expected("expectMetalSpotPrices"), database.metalSpotPriceDao().getAll().size.toLong())

        assertEquals(
            expected("expectDebtTotalMinor"),
            database.debtDao().getAll().sumOf { it.currentBalanceMinor }
        )
    }

    @Test
    fun aWrongPassphraseIsRefusedAndChangesNothing() = runBlocking {
        database = freshDatabase()
        val repository = repositoryFor(database)

        // Narrow on purpose. Catching Throwable here made a missing fixture
        // look like a correct refusal, so this test passed while testing
        // nothing at all.
        val refused = try {
            fixtures.open("restore-from-server.qlbk").use {
                repository.import(it, "WRONG-WRONG-WRONG-WRNG".toCharArray())
            }
            false
        } catch (expected: WrongBackupPassphraseException) {
            true
        }

        assertTrue("a wrong passphrase must be refused", refused)
        // The important half. A partial import would leave balances that do not
        // match their own history, which is worse than no import at all.
        assertEquals(0, database.debtDao().getAll().size)
    }
}
