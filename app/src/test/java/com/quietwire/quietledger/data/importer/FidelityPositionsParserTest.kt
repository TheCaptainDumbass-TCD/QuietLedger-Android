package com.quietwire.quietledger.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

/**
 * Runs against the real export in samples/fidelity, wired onto the test
 * classpath by the app build file.
 */
class FidelityPositionsParserTest {

    private val export: String by lazy {
        val path = "/fidelity/positions.redacted.csv"
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: fail("Missing fixture $path").let { error("unreachable") }
    }

    private fun parsed(): FidelityImport = FidelityPositionsParser.parse(export)

    @Test
    fun `parses every holding and the cash row`() {
        val result = parsed()
        assertEquals(
            listOf("SPAXX", "FSKAX", "FTIHX", "FXNAX", "FSPSX", "FSSNX", "FNILX"),
            result.positions.map { it.symbol }
        )
    }

    @Test
    fun `strips the footnote asterisks from SPAXX`() {
        val spaxx = parsed().positions.first { it.symbol == "SPAXX" }
        assertEquals("SPAXX", spaxx.symbol)
    }

    @Test
    fun `treats the money market row as cash rather than a holding`() {
        val result = parsed()
        val spaxx = result.positions.first { it.symbol == "SPAXX" }

        assertTrue("SPAXX should be flagged as cash", spaxx.isCash)
        assertNull("money market rows carry no quantity", spaxx.quantity)
        assertNull("money market rows carry no price", spaxx.lastPrice)
        assertEquals(125.40, spaxx.currentValue!!, 0.001)

        assertEquals(6, result.holdings.size)
        assertEquals(125.40, result.cashTotal, 0.001)
    }

    @Test
    fun `keeps missing cost basis as null instead of zero`() {
        // FNILX has "--" for gain/loss and cost basis. Zero would read as
        // "no gain", which is a different and wrong statement.
        val fnilx = parsed().positions.first { it.symbol == "FNILX" }

        assertNull(fnilx.totalGainLossDollar)
        assertNull(fnilx.costBasisTotal)
        assertNull(fnilx.averageCostBasis)

        assertEquals(150.000, fnilx.quantity!!, 0.0001)
        assertEquals(22.40, fnilx.lastPrice!!, 0.001)
        assertEquals(3360.00, fnilx.currentValue!!, 0.001)
    }

    @Test
    fun `reads a fully populated holding`() {
        val fskax = parsed().positions.first { it.symbol == "FSKAX" }

        assertEquals("Traditional IRA", fskax.accountName)
        assertEquals("FIDELITY TOTAL MARKET INDEX FUND", fskax.description)
        assertEquals(12.345, fskax.quantity!!, 0.0001)
        assertEquals(150.20, fskax.lastPrice!!, 0.001)
        assertEquals(1854.22, fskax.currentValue!!, 0.001)
        assertEquals(254.22, fskax.totalGainLossDollar!!, 0.001)
        assertEquals(1600.00, fskax.costBasisTotal!!, 0.001)
        assertEquals(129.61, fskax.averageCostBasis!!, 0.001)
    }

    @Test
    fun `skips the pending activity row`() {
        val result = parsed()

        assertTrue(
            "Pending activity must not become a holding",
            result.positions.none { it.symbol.contains("PENDING", ignoreCase = true) }
        )
        assertEquals(1, result.skipped.size)
        assertEquals("Pending activity", result.skipped.first().label)
    }

    @Test
    fun `stops before the disclaimer text`() {
        val result = parsed()
        // The disclaimers are long quoted paragraphs. If the reader mishandled
        // quoting they would show up as extra positions.
        assertEquals(7, result.positions.size)
        assertTrue(result.positions.none { it.symbol.length > 10 })
    }

    @Test
    fun `reads the download timestamp from the footer`() {
        // "Date downloaded Oct-02-2026 08:15 p.m ET"
        assertEquals(LocalDateTime.of(2026, 10, 2, 20, 15), parsed().downloadedAt)
    }

    @Test
    fun `current values add up to the account total`() {
        val total = parsed().positions.sumOf { it.currentValue ?: 0.0 }
        // Every current value in the sample, added up.
        assertEquals(7527.30, total, 0.01)
    }
}
