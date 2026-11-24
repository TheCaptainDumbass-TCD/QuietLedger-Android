package com.quietwire.quietledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.importer.FidelityPositionsParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holdings come only from a positions export, so these tests run the real
 * Fidelity file through the real parser and into the real database.
 *
 * The chain matters more than any single step. The parser was already tested
 * against this file, but nothing previously checked that what it produces
 * survives conversion into integer cents and comes back out as the same total.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldingsRepositoryTest {

    private lateinit var database: QuietLedgerDatabase
    private lateinit var holdings: HoldingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuietLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        holdings = HoldingsRepository(object : DatabaseHolder {
            override fun require() = database
        })
    }

    @After
    fun tearDown() = database.close()

    private fun realExport(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("fidelity/positions.redacted.csv")
    ) { "sample export missing from test resources" }.bufferedReader().readText()

    private fun parsed() = FidelityPositionsParser.parse(realExport())

    @Test
    fun `the real export imports and totals`() = runTest {
        val result = holdings.applyImport(parsed())

        assertTrue("positions were found", result.positions > 0)
        assertTrue("the total is a real figure", result.total.isPositive)
        assertNotNull("the as-of date comes from the footer", result.asOf)

        // What goes in comes back out, to the cent.
        assertEquals(result.total, holdings.observeTotal().first())
    }

    @Test
    fun `the money market position is recorded as cash, not a holding`() = runTest {
        holdings.applyImport(parsed())

        val spaxx = holdings.observeAll().first().single { it.symbol == "SPAXX" }
        assertTrue("SPAXX is cash", spaxx.isCash)
        // Value but no shares, which is exactly how the export reports it.
        assertNull(spaxx.quantity)
        assertTrue(spaxx.currentValue.isPositive)
    }

    @Test
    fun `a missing cost basis stays missing rather than becoming zero`() = runTest {
        holdings.applyImport(parsed())

        // FNILX has "--" for cost basis in this file. Zero would imply the
        // whole value is gain, which would be a fabricated number.
        val withoutBasis = holdings.observeAll().first().filter { it.costBasis == null }
        assertTrue("at least one position has no cost basis", withoutBasis.isNotEmpty())
        withoutBasis.forEach { assertNull(it.gainLossFraction) }
    }

    @Test
    fun `fractional share counts survive the round trip`() = runTest {
        holdings.applyImport(parsed())

        val funds = holdings.observeAll().first().filter { it.quantity != null }
        assertTrue(funds.isNotEmpty())
        // Reinvested dividends produce fractional holdings; a whole-number
        // quantity would mean the scale had been lost.
        assertTrue(
            "expected at least one fractional quantity",
            funds.any { it.quantity!!.stripTrailingZeros().scale() > 0 }
        )
    }

    @Test
    fun `re-importing replaces rather than duplicating`() = runTest {
        val first = holdings.applyImport(parsed())
        val countAfterFirst = holdings.observeAll().first().size

        val second = holdings.applyImport(parsed())

        assertEquals(countAfterFirst, holdings.observeAll().first().size)
        assertEquals(first.total, second.total)
        assertEquals("the previous set was reported as replaced", countAfterFirst, second.removed)
    }

    /**
     * The failure this design exists to prevent. A positions file is a
     * snapshot, so a symbol absent from the newer file has been sold. Merging
     * would leave it in the list and inflate net worth indefinitely.
     */
    @Test
    fun `a position sold between exports disappears`() = runTest {
        holdings.applyImport(parsed())
        val before = holdings.observeAll().first()
        val droppedSymbol = before.first { !it.isCash }.symbol

        // The same file with one position removed.
        val lines = realExport().lines()
        val trimmed = lines.filterNot { it.startsWith("\"") && it.contains(droppedSymbol) }
            .filterNot { it.split(",").getOrNull(2)?.trim()?.trim('"') == droppedSymbol }
            .joinToString("\n")

        val result = holdings.applyImport(FidelityPositionsParser.parse(trimmed))

        val after = holdings.observeAll().first()
        assertTrue(
            "$droppedSymbol should be gone",
            after.none { it.symbol == droppedSymbol }
        )
        assertTrue("the total must fall", result.total < before.sumOfValues())
    }

    @Test
    fun `an empty file is refused rather than wiping what is there`() = runTest {
        holdings.applyImport(parsed())
        val before = holdings.observeTotal().first()

        val error = runCatching {
            holdings.applyImport(FidelityPositionsParser.parse("Nothing useful here\n"))
        }.exceptionOrNull()

        assertNotNull("an empty import must fail", error)
        assertEquals("existing holdings untouched", before, holdings.observeTotal().first())
    }

    @Test
    fun `the as-of date is exposed so staleness can be shown`() = runTest {
        holdings.applyImport(parsed())
        val asOf = holdings.observeAsOf().first()

        assertNotNull(asOf)
        // Comes from the file footer, not from the clock at import time.
        assertEquals(parsed().downloadedAt!!.year, 2026)
    }

    /**
     * The reported bug: importing again doubled the totals.
     *
     * The delete was scoped to `positions.first().accountName`, so a second
     * account's rows were never replaced, and any change to the label meant
     * nothing matched and the new rows were simply added on top.
     */
    @Test
    fun `re-importing a file with two accounts replaces both`() = runTest {
        // The real export holds one account, "Traditional IRA". Copying its
        // position rows under a second label reproduces a multi-account export.
        val lines = realExport().lines()
        val positionRows = lines.filter { it.contains("Traditional IRA") }
        assertTrue("fixture should contain the account label", positionRows.isNotEmpty())

        val lastPosition = lines.indexOfLast { it.contains("Traditional IRA") }
        val twoAccounts = (
            lines.take(lastPosition + 1) +
                positionRows.map { it.replace("Traditional IRA", "Roth IRA") } +
                lines.drop(lastPosition + 1)
            ).joinToString("\n")

        val parsedTwo = FidelityPositionsParser.parse(twoAccounts)
        assertEquals(
            "both accounts should parse",
            2,
            parsedTwo.positions.map { it.accountName }.distinct().size
        )

        val first = holdings.applyImport(parsedTwo)
        val countAfterFirst = holdings.observeAll().first().size

        val second = holdings.applyImport(FidelityPositionsParser.parse(twoAccounts))

        assertEquals(
            "a re-import must replace every holding, not add to them",
            countAfterFirst,
            holdings.observeAll().first().size
        )
        assertEquals(first.total, holdings.observeTotal().first())
        assertEquals("all previous rows reported as replaced", countAfterFirst, second.removed)
    }

    @Test
    fun `an import replaces holdings whose account label has changed`() = runTest {
        holdings.applyImport(parsed())
        val before = holdings.observeTotal().first()
        val countBefore = holdings.observeAll().first().size
        assertTrue(countBefore > 0)

        // Fidelity relabels the account, so nothing matches the old name and
        // the previous rows would survive alongside the new ones.
        val relabelled = realExport().replace("Traditional IRA", "Rollover IRA")
        holdings.applyImport(FidelityPositionsParser.parse(relabelled))

        assertEquals(
            "a changed account label must not leave the old rows behind",
            countBefore,
            holdings.observeAll().first().size
        )
        assertEquals(before, holdings.observeTotal().first())
    }
}

private fun List<com.quietwire.quietledger.domain.model.Holding>.sumOfValues(): Money =
    fold(Money.ZERO) { acc, h -> acc + h.currentValue }
