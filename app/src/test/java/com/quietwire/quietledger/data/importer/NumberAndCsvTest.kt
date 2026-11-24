package com.quietwire.quietledger.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberParsingTest {

    @Test
    fun `handles the money shapes Fidelity writes`() {
        assertEquals(34.54, FidelityPositionsParser.parseNumber("$34.54")!!, 0.0001)
        assertEquals(13.76, FidelityPositionsParser.parseNumber("+$13.76")!!, 0.0001)
        assertEquals(-0.08, FidelityPositionsParser.parseNumber("-$0.08")!!, 0.0001)
        assertEquals(-15.0, FidelityPositionsParser.parseNumber("-$15.00")!!, 0.0001)
        assertEquals(1234.56, FidelityPositionsParser.parseNumber("$1,234.56")!!, 0.0001)
        assertEquals(1234567.89, FidelityPositionsParser.parseNumber("$1,234,567.89")!!, 0.0001)
    }

    @Test
    fun `handles percentages`() {
        assertEquals(-0.24, FidelityPositionsParser.parseNumber("-0.24%")!!, 0.0001)
        assertEquals(3.40, FidelityPositionsParser.parseNumber("+3.40%")!!, 0.0001)
        assertEquals(2.21, FidelityPositionsParser.parseNumber("2.21%")!!, 0.0001)
    }

    @Test
    fun `unavailable markers become null rather than zero`() {
        assertNull(FidelityPositionsParser.parseNumber("--"))
        assertNull(FidelityPositionsParser.parseNumber(""))
        assertNull(FidelityPositionsParser.parseNumber("   "))
        assertNull(FidelityPositionsParser.parseNumber("n/a"))
    }

    @Test
    fun `handles accounting style negatives`() {
        assertEquals(-12.34, FidelityPositionsParser.parseNumber("($12.34)")!!, 0.0001)
    }

    @Test
    fun `garbage does not throw`() {
        assertNull(FidelityPositionsParser.parseNumber("abc"))
    }
}

class CsvReaderTest {

    @Test
    fun `splits a plain row`() {
        assertEquals(
            listOf(listOf("a", "b", "c")),
            CsvReader.parse("a,b,c")
        )
    }

    @Test
    fun `keeps commas inside quoted fields`() {
        val rows = CsvReader.parse("\"one, two, three\",second")
        assertEquals(2, rows[0].size)
        assertEquals("one, two, three", rows[0][0])
        assertEquals("second", rows[0][1])
    }

    @Test
    fun `handles doubled quotes as an escape`() {
        val rows = CsvReader.parse("\"he said \"\"hello\"\"\",x")
        assertEquals("he said \"hello\"", rows[0][0])
    }

    @Test
    fun `preserves empty fields`() {
        val rows = CsvReader.parse(",,x,,")
        assertEquals(listOf("", "", "x", "", ""), rows[0])
    }

    @Test
    fun `treats CRLF as a single row break`() {
        val rows = CsvReader.parse("a,b\r\nc,d")
        assertEquals(2, rows.size)
        assertEquals(listOf("c", "d"), rows[1])
    }
}

/**
 * The parser limits exist because this is the one place in the app that handles
 * wholly untrusted input with no cryptographic gate in front of it. Each test
 * here is a denial-of-service attempt that must fail cleanly rather than taking
 * the process down.
 */
class CsvLimitsTest {

    @Test
    fun `an oversized file is refused before it is parsed`() {
        val huge = "a,b\n".repeat(CsvReader.MAX_INPUT_CHARS / 4 + 10)
        val error = org.junit.Assert.assertThrows(CsvTooLargeException::class.java) {
            CsvReader.parse(huge)
        }
        org.junit.Assert.assertTrue(error.message!!.contains("larger than"))
    }

    @Test
    fun `too many rows is refused`() {
        val many = "a\n".repeat(CsvReader.MAX_ROWS + 5)
        org.junit.Assert.assertThrows(CsvTooLargeException::class.java) {
            CsvReader.parse(many)
        }
    }

    @Test
    fun `too many columns in a single row is refused`() {
        // A row of nothing but separators is the cheapest way to force a parser
        // to allocate an enormous list.
        val wide = ",".repeat(CsvReader.MAX_COLUMNS_PER_ROW + 5)
        org.junit.Assert.assertThrows(CsvTooLargeException::class.java) {
            CsvReader.parse(wide)
        }
    }

    @Test
    fun `an absurdly long single field is refused`() {
        val longField = "\"" + "x".repeat(CsvReader.MAX_FIELD_CHARS + 10) + "\""
        org.junit.Assert.assertThrows(CsvTooLargeException::class.java) {
            CsvReader.parse(longField)
        }
    }

    @Test
    fun `an unterminated quote does not hang or overrun`() {
        // Missing closing quote means everything after it is one field. It must
        // terminate and produce something, not loop.
        val rows = CsvReader.parse("a,\"unclosed field continues to the end")
        org.junit.Assert.assertEquals(1, rows.size)
        org.junit.Assert.assertEquals(2, rows[0].size)
    }

    @Test
    fun `a real export is nowhere near the limits`() {
        // Sanity check that the caps are generous rather than tight. A genuine
        // Fidelity export is a couple of kilobytes.
        org.junit.Assert.assertTrue(CsvReader.MAX_INPUT_CHARS > 1_000_000)
        org.junit.Assert.assertTrue(CsvReader.MAX_ROWS > 10_000)
    }
}
