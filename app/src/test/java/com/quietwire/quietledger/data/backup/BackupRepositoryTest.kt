package com.quietwire.quietledger.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.model.DebtCategory
import com.quietwire.quietledger.data.repository.DebtRepository
import com.quietwire.quietledger.data.repository.HoldingsRepository
import com.quietwire.quietledger.data.importer.FidelityPositionsParser
import com.quietwire.quietledger.domain.model.Debt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRepositoryTest {

    private lateinit var database: QuietLedgerDatabase
    private lateinit var debts: DebtRepository
    private lateinit var backups: BackupRepository
    private lateinit var holder: DatabaseHolder

    private val passphrase = "export passphrase"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuietLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        holder = object : DatabaseHolder {
            override fun require() = database
        }
        debts = DebtRepository(holder)
        backups = BackupRepository(holder, appVersion = "0.1.0-test")
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seed() {
        val card = debts.add(
            Debt(
                name = "Visa",
                category = DebtCategory.CREDIT_CARD,
                currentBalance = Money.parseOrNull("1254.63")!!,
                scheduledPayment = Money.parseOrNull("158.00")!!,
                paymentFrequency = Frequency.MONTHLY,
                aprBasisPoints = 499
            )
        )
        debts.add(
            Debt(
                name = "car",
                category = DebtCategory.VEHICLE_LOAN,
                currentBalance = Money.parseOrNull("2000.00")!!,
                scheduledPayment = Money.parseOrNull("100.00")!!,
                paymentFrequency = Frequency.WEEKLY,
                aprBasisPoints = 800
            )
        )
        debts.recordPayment(card, Money.parseOrNull("54.63")!!, note = "round it down")
    }

    private suspend fun exportBytes(): ByteArray =
        ByteArrayOutputStream().also { backups.export(it, passphrase.toCharArray()) }.toByteArray()

    @Test
    fun `export then import restores everything exactly`() = runTest {
        seed()
        val before = debts.getOpen().sortedBy { it.name }
        val historyBefore = debts.observeHistory(before.first { it.name == "Visa" }.id).first()
        val bytes = exportBytes()

        // Wipe it the way a reinstall would.
        database.clearAllTables()
        assertTrue(debts.getOpen().isEmpty())

        val summary = backups.import(bytes.inputStream(), passphrase.toCharArray())

        assertEquals(2, summary.debts)
        assertEquals(3, summary.balanceChanges)
        assertNotNull(summary.exportedAt)

        val after = debts.getOpen().sortedBy { it.name }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) ->
            assertEquals(b.name, a.name)
            assertEquals(b.currentBalance, a.currentBalance)
            assertEquals(b.scheduledPayment, a.scheduledPayment)
            assertEquals(b.paymentFrequency, a.paymentFrequency)
            assertEquals(b.aprBasisPoints, a.aprBasisPoints)
            assertEquals(b.category, a.category)
            assertEquals(b.originalBalance, a.originalBalance)
        }

        // History has to survive too, or balances lose their provenance.
        val historyAfter = debts.observeHistory(after.first { it.name == "Visa" }.id).first()
        assertEquals(historyBefore.size, historyAfter.size)
        assertEquals(historyBefore.map { it.delta }, historyAfter.map { it.delta })
        assertEquals(historyBefore.map { it.source }, historyAfter.map { it.source })
        assertEquals("round it down", historyAfter.first().note)
    }

    @Test
    fun `money survives the round trip to the cent`() = runTest {
        // Awkward amounts that a float would mangle.
        listOf("0.01", "1254.63", "99999.99", "0.99").forEach { amount ->
            debts.add(
                Debt(
                    name = "debt $amount",
                    category = DebtCategory.OTHER,
                    currentBalance = Money.parseOrNull(amount)!!
                )
            )
        }
        val expected = debts.getOpen().associate { it.name to it.currentBalance }

        val bytes = exportBytes()
        database.clearAllTables()
        backups.import(bytes.inputStream(), passphrase.toCharArray())

        val actual = debts.getOpen().associate { it.name to it.currentBalance }
        assertEquals(expected, actual)
    }

    @Test
    fun `import replaces rather than merges`() = runTest {
        seed()
        val bytes = exportBytes()

        debts.add(
            Debt(
                name = "added after the export",
                category = DebtCategory.OTHER,
                currentBalance = Money.parseOrNull("500.00")!!
            )
        )
        assertEquals(3, debts.getOpen().size)

        backups.import(bytes.inputStream(), passphrase.toCharArray())

        assertEquals(2, debts.getOpen().size)
        assertTrue(debts.getOpen().none { it.name == "added after the export" })
    }

    @Test
    fun `a failed import leaves the existing data untouched`() = runTest {
        seed()
        val expected = debts.getOpen().map { it.name }.sorted()

        assertThrows(WrongBackupPassphraseException::class.java) {
            kotlinx.coroutines.runBlocking {
                backups.import(exportBytes().inputStream(), "wrong passphrase here".toCharArray())
            }
        }

        assertEquals(expected, debts.getOpen().map { it.name }.sorted())
    }

    @Test
    fun `an empty database exports and imports cleanly`() = runTest {
        val bytes = exportBytes()
        val summary = backups.import(bytes.inputStream(), passphrase.toCharArray())
        assertEquals(0, summary.total)
    }

    @Test
    fun `inspect reports contents without changing anything`() = runTest {
        seed()
        val bytes = exportBytes()
        val before = debts.getOpen().size

        val summary = backups.inspect(bytes.inputStream(), passphrase.toCharArray())

        assertEquals(2, summary.debts)
        assertEquals(before, debts.getOpen().size)
    }

    @Test
    fun `the suggested filename carries the extension`() {
        assertTrue(backups.suggestedFileName().endsWith(".qlbk"))
        assertTrue(backups.suggestedFileName().startsWith("quietledger-"))
    }

    /**
     * A converted desktop file carries no retirement positions, because the
     * desktop never had them: they come from a broker CSV.
     *
     * Restoring such a file must leave the IRA alone. Wiping it would destroy
     * data the backup never claimed to hold, and the restore would report
     * success while the positions silently disappeared. That nearly shipped.
     */
    @Test
    fun `restoring a file without holdings leaves existing holdings alone`() = runTest {
        val csv = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "fidelity/positions.redacted.csv"
            )
        ).bufferedReader().readText()
        HoldingsRepository(holder).applyImport(FidelityPositionsParser.parse(csv))
        val heldBefore = database.holdingDao().getAll().size
        assertTrue("there is an IRA to lose", heldBefore > 0)

        // An export with everything except holdings, as the converter produces.
        val withoutHoldings = BackupFile(
            debts = listOf(BackupDebt(name = "Card", currentBalanceMinor = 1000))
        )
        val bytes = ByteArrayOutputStream()
        BackupEnvelope.write(bytes, Json.encodeToString(withoutHoldings), passphrase.toCharArray())

        val summary = backups.import(
            ByteArrayInputStream(bytes.toByteArray()), passphrase.toCharArray()
        )

        assertEquals(0, summary.holdings)
        assertEquals(
            "the IRA must survive a restore that never mentioned it",
            heldBefore,
            database.holdingDao().getAll().size
        )
    }

    @Test
    fun `a backup made by the app carries holdings and restores them`() = runTest {
        val csv = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "fidelity/positions.redacted.csv"
            )
        ).bufferedReader().readText()
        HoldingsRepository(holder).applyImport(FidelityPositionsParser.parse(csv))
        val expected = database.holdingDao().getAll().size

        val exported = ByteArrayOutputStream()
        backups.export(exported, passphrase.toCharArray())

        database.holdingDao().deleteAll()
        assertEquals(0, database.holdingDao().getAll().size)

        val summary = backups.import(
            ByteArrayInputStream(exported.toByteArray()), passphrase.toCharArray()
        )

        assertEquals(expected, summary.holdings)
        assertEquals(expected, database.holdingDao().getAll().size)
    }

}
