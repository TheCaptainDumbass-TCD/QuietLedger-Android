package com.quietwire.quietledger.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class MoneyTest {

    @Test
    fun `addition is exact where doubles are not`() {
        // 0.1 + 0.2 != 0.3 in floating point. It does here.
        val sum = Money.parseOrNull("0.10")!! + Money.parseOrNull("0.20")!!
        assertEquals(Money.parseOrNull("0.30"), sum)
        assertEquals(30L, sum.minorUnits)
    }

    @Test
    fun `repeated subtraction lands exactly on zero`() {
        // The desktop leaves -8.5e-14 after this loop, which makes
        // "is it paid off" a coin toss.
        var balance = Money.ofMajor(1000)
        val payment = Money.parseOrNull("83.33")!!
        repeat(12) { balance -= payment }

        assertEquals(Money.parseOrNull("0.04"), balance)
        assertTrue(balance.isPositive)
    }

    @Test
    fun `summing the Fidelity positions is exact`() {
        val values = listOf("92.71", "418.10", "411.92", "411.71", "403.18", "398.96", "2070.30")
            .map { Money.parseOrNull(it)!! }
        val total = values.fold(Money.ZERO) { acc, m -> acc + m }
        assertEquals(Money.parseOrNull("4206.88"), total)
        assertEquals(420688L, total.minorUnits)
    }

    @Test
    fun `paymentsToClear rounds up, unlike the desktop shortcut`() {
        // The desktop uses int(balance / payment + 0.99), which reports a
        // month early whenever the final payment is a small remainder.
        fun clear(balance: String, payment: String) =
            Money.parseOrNull(balance)!!.paymentsToClear(Money.parseOrNull(payment)!!)

        assertEquals(2, clear("1000.00", "500.00"))   // exact, desktop agrees
        assertEquals(3, clear("1000.50", "500.00"))   // desktop says 2
        assertEquals(3, clear("100.10", "50.00"))     // desktop says 2
        assertEquals(3, clear("1005.00", "500.00"))
        assertEquals(1, clear("0.01", "500.00"))
    }

    @Test
    fun `paymentsToClear handles the degenerate cases`() {
        assertEquals(0, Money.ZERO.paymentsToClear(Money.ofMajor(100)))
        assertEquals(0, (-Money.ofMajor(50)).paymentsToClear(Money.ofMajor(100)))
        // A debt with no payment set never clears. Null, not -1, so it cannot
        // be mistaken for a month count and used in arithmetic.
        assertNull(Money.ofMajor(100).paymentsToClear(Money.ZERO))
        assertNull(Money.ofMajor(100).paymentsToClear(-Money.ofMajor(5)))
    }

    @Test
    fun `division rounds explicitly`() {
        assertEquals(Money.parseOrNull("33.33"), Money.ofMajor(100).divide(3))
        assertEquals(Money.parseOrNull("33.34"), Money.ofMajor(100).divide(3, RoundingMode.CEILING))
        assertEquals(Money.parseOrNull("33.33"), Money.ofMajor(100).divide(3, RoundingMode.FLOOR))
    }

    @Test
    fun `parses the shapes people and brokers write`() {
        assertEquals(123456L, Money.parseOrNull("$1,234.56")!!.minorUnits)
        assertEquals(123456L, Money.parseOrNull("1234.56")!!.minorUnits)
        assertEquals(-8L, Money.parseOrNull("-$0.08")!!.minorUnits)
        assertEquals(1376L, Money.parseOrNull("+$13.76")!!.minorUnits)
        assertEquals(-1234L, Money.parseOrNull("($12.34)")!!.minorUnits)
        assertEquals(500L, Money.parseOrNull("5")!!.minorUnits)
        assertEquals(50L, Money.parseOrNull(".50")!!.minorUnits)
    }

    @Test
    fun `rejects rather than guesses at nonsense`() {
        assertNull(Money.parseOrNull(""))
        assertNull(Money.parseOrNull("   "))
        assertNull(Money.parseOrNull("abc"))
        assertNull(Money.parseOrNull("--"))
        assertNull(Money.parseOrNull("1.2.3"))
        assertNull(Money.parseOrNull("12abc"))
    }

    @Test
    fun `converting a desktop double rounds to the nearest cent`() {
        assertEquals(41810L, Money.fromDouble(418.10).minorUnits)
        assertEquals(9271L, Money.fromDouble(92.71).minorUnits)
        // A value that has no exact double representation still lands right.
        assertEquals(10L, Money.fromDouble(0.1).minorUnits)
        assertEquals(30L, Money.fromDouble(0.1 + 0.2).minorUnits)
    }

    @Test
    fun `overflow throws instead of silently wrapping`() {
        val huge = Money.ofMinor(Long.MAX_VALUE)
        try {
            huge + Money.ofMinor(1)
            throw AssertionError("expected an arithmetic overflow")
        } catch (expected: ArithmeticException) {
            // Correct. Wrapping to a negative balance would be far worse.
        }
    }

    @Test
    fun `formats for display`() {
        assertEquals("$1,234.56", Money.parseOrNull("1234.56")!!.format())
        assertEquals("$0.00", Money.ZERO.format())
        assertEquals("-$12.34", Money.parseOrNull("-12.34")!!.format())
    }

    @Test
    fun `round trips through BigDecimal`() {
        val m = Money.parseOrNull("4206.88")!!
        assertEquals(BigDecimal("4206.88"), m.toBigDecimal())
        assertEquals(m, Money.of(m.toBigDecimal()))
    }

    @Test
    fun `orders correctly`() {
        val sorted = listOf("10.00", "-5.00", "0.00", "2.50")
            .map { Money.parseOrNull(it)!! }
            .sorted()
        assertEquals(
            listOf("-5.00", "0.00", "2.50", "10.00").map { Money.parseOrNull(it)!! },
            sorted
        )
    }
}
