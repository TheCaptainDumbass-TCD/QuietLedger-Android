package com.quietwire.quietledger.domain.payoff

import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.DebtCategory
import com.quietwire.quietledger.domain.model.Debt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PayoffCalculatorTest {

    private val calculator = PayoffCalculator()
    private val start = LocalDate.of(2026, 1, 15)

    private fun debt(
        name: String,
        balance: String,
        payment: String,
        apr: Int? = null,
        frequency: Frequency = Frequency.MONTHLY,
        category: DebtCategory = DebtCategory.CREDIT_CARD
    ) = Debt(
        id = name.hashCode().toLong(),
        name = name,
        category = category,
        currentBalance = Money.parseOrNull(balance)!!,
        scheduledPayment = Money.parseOrNull(payment)!!,
        paymentFrequency = frequency,
        aprBasisPoints = apr
    )

    @Test
    fun `no debts gives an empty plan rather than an error`() {
        val plan = calculator.project(emptyList(), start)
        assertEquals(0, plan.monthsToDebtFree)
        assertTrue(plan.projections.isEmpty())
    }

    @Test
    fun `a single interest free debt takes exactly the right number of months`() {
        val plan = calculator.project(listOf(debt("Card", "1000.00", "500.00")), start)
        assertEquals(2, plan.monthsToDebtFree)
        assertEquals(LocalDate.of(2026, 3, 15), plan.debtFreeDate)
        assertEquals(Money.parseOrNull("1000.00"), plan.totalPrincipal)
        assertEquals(Money.ZERO, plan.totalInterest)
    }

    @Test
    fun `a trailing part payment counts as a whole month`() {
        // The desktop's int(x + 0.99) shortcut says 2 months here. It is 3:
        // 500, 500, then 0.50.
        val plan = calculator.project(listOf(debt("Card", "1000.50", "500.00")), start)
        assertEquals(3, plan.monthsToDebtFree)
        assertEquals(LocalDate.of(2026, 4, 15), plan.debtFreeDate)
        // And it must not overpay.
        assertEquals(Money.parseOrNull("1000.50"), plan.totalPrincipal)
    }

    @Test
    fun `dates advance by calendar months, not 30 day blocks`() {
        // 12 months from 15 January 2026 is 15 January 2027. Adding 12 * 30
        // days, as the desktop does, lands on 11 January, five days early.
        val plan = calculator.project(listOf(debt("Loan", "1200.00", "100.00")), start)
        assertEquals(12, plan.monthsToDebtFree)
        assertEquals(LocalDate.of(2027, 1, 15), plan.debtFreeDate)
    }

    @Test
    fun `a debt with no payment is reported, not silently corrupting the plan`() {
        // In the desktop this produced -1 months, which was then added to the
        // running total and subtracted from later balances, quietly breaking
        // every projection after it.
        val debts = listOf(
            debt("Stuck", "500.00", "0.00"),
            debt("Card", "1000.00", "250.00")
        )
        val plan = calculator.project(debts, start, rollOverClearedPayments = false)

        val stuck = plan.projections.first { it.debt.name == "Stuck" }
        assertFalse(stuck.willClear)
        assertEquals(UnpayableReason.NO_PAYMENT, stuck.unpayableReason)
        assertNull(stuck.monthsToPayoff)

        // The healthy debt is unaffected.
        val card = plan.projections.first { it.debt.name == "Card" }
        assertEquals(4, card.monthsToPayoff)

        // And the plan as a whole honestly reports that it never finishes.
        assertNull(plan.debtFreeDate)
        assertFalse(plan.clearsCompletely)
    }

    @Test
    fun `snowball rolls a cleared payment into the next debt`() {
        val debts = listOf(
            debt("Small", "300.00", "100.00"),
            debt("Large", "1000.00", "100.00")
        )

        val withRollover = calculator.project(debts, start, rollOverClearedPayments = true)
        val without = calculator.project(debts, start, rollOverClearedPayments = false)

        // Small clears in 3 months either way.
        assertEquals(3, withRollover.projections.first { it.debt.name == "Small" }.monthsToPayoff)

        // Large: 1000 at 100/month is 10 months alone. With the extra 100
        // rolling in from month 4 it finishes sooner.
        val largeWith = withRollover.projections.first { it.debt.name == "Large" }.monthsToPayoff!!
        val largeWithout = without.projections.first { it.debt.name == "Large" }.monthsToPayoff!!
        assertEquals(10, largeWithout)
        assertTrue("rollover should finish sooner", largeWith < largeWithout)
        assertEquals(7, largeWith)
    }

    @Test
    fun `payoff order is smallest balance first`() {
        val debts = listOf(
            debt("Big", "5000.00", "200.00"),
            debt("Tiny", "100.00", "50.00"),
            debt("Middle", "900.00", "100.00")
        )
        val plan = calculator.project(debts, start)
        val order = plan.projections.mapNotNull { p -> p.payoffOrder?.let { it to p.debt.name } }
            .sortedBy { it.first }
            .map { it.second }
        assertEquals(listOf("Tiny", "Middle", "Big"), order)
    }

    @Test
    fun `interest is charged and reported`() {
        // 1000 at 12 percent APR is 1 percent a month.
        val plan = calculator.project(listOf(debt("Card", "1000.00", "100.00", apr = 1200)), start)

        assertTrue("interest should be charged", plan.totalInterest.isPositive)
        // Interest makes it take longer than the 10 months it would at zero.
        assertTrue("should take longer than interest free", plan.monthsToDebtFree!! > 10)
        assertEquals(11, plan.monthsToDebtFree)
    }

    @Test
    fun `a debt whose interest outruns the payment is flagged, not looped forever`() {
        // 10,000 at 24 percent is 200 a month in interest against a 50 payment.
        val plan = calculator.project(listOf(debt("Sinking", "10000.00", "50.00", apr = 2400)), start)

        val projection = plan.projections.single()
        assertFalse(projection.willClear)
        assertEquals(UnpayableReason.INTEREST_EXCEEDS_PAYMENT, projection.unpayableReason)
        assertNull(plan.debtFreeDate)
    }

    @Test
    fun `weekly payments are converted properly, not as four per month`() {
        // 100 a week is 433.33 a month, not 400. Over 2000 that is 5 months
        // rather than the 5 the desktop would also say here, so check the
        // monthly figure directly too.
        val weekly = debt("Weekly", "2000.00", "100.00", frequency = Frequency.WEEKLY)
        assertEquals(Money.parseOrNull("433.33"), weekly.monthlyPayment)

        val plan = calculator.project(listOf(weekly), start)
        assertEquals(5, plan.monthsToDebtFree)
    }

    @Test
    fun `extra payment shortens the plan`() {
        val debts = listOf(debt("Card", "1200.00", "100.00"))
        val base = calculator.project(debts, start)
        val boosted = calculator.project(debts, start, extraMonthlyPayment = Money.parseOrNull("100.00")!!)

        assertEquals(12, base.monthsToDebtFree)
        assertEquals(6, boosted.monthsToDebtFree)
    }

    private fun weeklyDebt(name: String, balance: String, weekly: String) = Debt(
        id = name.hashCode().toLong(),
        name = name,
        category = DebtCategory.CREDIT_CARD,
        currentBalance = Money.parseOrNull(balance)!!,
        scheduledPayment = Money.parseOrNull(weekly)!!,
        paymentFrequency = Frequency.WEEKLY
    )

    @Test
    fun `surplus rolls to the next debt within the same month, when rollover is on`() {
        // Small needs 20, its payment is 100, so 80 should not be wasted.
        val debts = listOf(
            debt("Small", "20.00", "100.00"),
            debt("Next", "500.00", "100.00")
        )
        val plan = calculator.project(debts, start, rollOverClearedPayments = true)

        assertEquals(1, plan.projections.first { it.debt.name == "Small" }.monthsToPayoff)
        // Next gets 100 of its own plus 80 spare in month 1, then 200 a month.
        val next = plan.projections.first { it.debt.name == "Next" }
        assertEquals(3, next.monthsToPayoff)
    }

    /**
     * Rollover is opt-in, and the default has to stay that way.
     *
     * Assuming a cleared debt's payment goes to the next one is a prediction
     * about behaviour, not arithmetic. The money might go to savings or
     * investing instead. A default that assumes it produces a debt-free date
     * nobody agreed to, and figures that look wrong: this case would report
     * three months rather than five.
     */
    @Test
    fun `by default a debt is projected on its own payment alone`() {
        val debts = listOf(
            debt("Small", "20.00", "100.00"),
            debt("Next", "500.00", "100.00")
        )
        val plan = calculator.project(debts, start)

        assertEquals(1, plan.projections.first { it.debt.name == "Small" }.monthsToPayoff)
        // 500 at 100 a month is five months, with nothing rolling in.
        assertEquals(5, plan.projections.first { it.debt.name == "Next" }.monthsToPayoff)
        assertEquals(5, plan.monthsToDebtFree)
    }

    @Test
    fun `a weekly payment on a large balance is not quietly accelerated`() {
        // The reported case: 9,985.97 at 125 a week, which is 541.67 a month.
        // Alongside a smaller debt paying 375 a week, rollover made it report
        // nine months instead of nineteen, which read as a calculation error.
        val debts = listOf(
            weeklyDebt("Northbridge CU", "9985.97", "125.00"),
            weeklyDebt("QuickSilver", "7259.85", "375.00")
        )

        val plain = calculator.project(debts, start)
        val northbridge = plain.projections.first { it.debt.name == "Northbridge CU" }
        assertEquals(Money.parseOrNull("541.67"), northbridge.debt.monthlyPayment)
        assertEquals(19, northbridge.monthsToPayoff)

        // With rollover on it really is nine, and the projection carries the
        // figure needed to explain that rather than leaving it unexplained.
        val rolled = calculator.project(debts, start, rollOverClearedPayments = true)
        val accelerated = rolled.projections.first { it.debt.name == "Northbridge CU" }
        assertEquals(9, accelerated.monthsToPayoff)
        assertEquals(19, accelerated.monthsOnOwnPayment)
        assertEquals(10, accelerated.monthsSavedByRollover)
        assertTrue(accelerated.acceleratedByRollover)
    }

    @Test
    fun `closed and zero balance debts are ignored`() {
        val debts = listOf(
            debt("Open", "500.00", "500.00"),
            debt("Zero", "0.00", "100.00"),
            debt("Closed", "999.00", "100.00").copy(closedAt = java.time.Instant.now())
        )
        val plan = calculator.project(debts, start)
        assertEquals(1, plan.projections.size)
        assertEquals("Open", plan.projections.single().debt.name)
    }

    @Test
    fun `comparison reports what the snowball actually saves`() {
        val debts = listOf(
            debt("Small", "300.00", "100.00"),
            debt("Large", "1000.00", "100.00")
        )
        val comparison = calculator.compare(debts, start)

        assertNotNull(comparison.monthsSaved)
        assertEquals(3, comparison.monthsSaved)
        assertEquals(7, comparison.snowball.monthsToDebtFree)
        assertEquals(10, comparison.withoutRollover.monthsToDebtFree)
    }

    @Test
    fun `with interest, total paid is the balance plus the interest and nothing more`() {
        // Reproduces what the app showed on device: outstanding 3254.63 and
        // interest 100.19 were reported with a total of 3455.01, which is
        // 100.19 too much. Interest was being counted once in interestPaid and
        // again inside principalPaid, because the payment covering it was
        // recorded as principal.
        val debts = listOf(
            debt("car", "2000.00", "200.00", apr = 800),
            debt("Finance One", "1254.63", "158.00", apr = 499)
        )
        val plan = calculator.project(debts, start)

        val startingBalances = Money.parseOrNull("3254.63")!!
        assertEquals(startingBalances, plan.totalPrincipal)
        assertEquals(startingBalances + plan.totalInterest, plan.totalPaid)
    }

    @Test
    fun `principal and interest add up to the total for every debt`() {
        val debts = listOf(
            debt("A", "1000.00", "150.00", apr = 1899),
            debt("B", "2500.00", "300.00", apr = 750)
        )
        val plan = calculator.project(debts, start)

        plan.projections.forEach {
            assertEquals(
                "principal plus interest must equal total for ${it.debt.name}",
                it.principalPaid + it.interestPaid,
                it.totalPaid
            )
        }
        assertEquals(
            plan.totalPrincipal + plan.totalInterest,
            plan.totalPaid
        )
    }

    @Test
    fun `principal paid equals the starting balance for a debt that clears`() {
        val debts = listOf(debt("Card", "1000.00", "100.00", apr = 1200))
        val plan = calculator.project(debts, start)
        val projection = plan.projections.single()

        assertTrue(projection.willClear)
        assertEquals(Money.parseOrNull("1000.00"), projection.principalPaid)
        assertTrue(projection.interestPaid.isPositive)
    }

    @Test
    fun `totals never exceed what is actually owed when there is no interest`() {
        val debts = listOf(
            debt("A", "333.33", "100.00"),
            debt("B", "666.67", "250.00")
        )
        val plan = calculator.project(debts, start)
        assertEquals(Money.parseOrNull("1000.00"), plan.totalPrincipal)
        assertEquals(Money.ZERO, plan.totalInterest)
    }

    /**
     * Owing nothing is the best outcome, not a failure.
     *
     * `clearsCompletely` was `debtFreeDate != null`, and a plan with nothing in
     * it has no date, so a zero balance reported "Not on track" in red across
     * the Overview and Payoff screens.
     */
    @Test
    fun `no debt at all is debt free, not off track`() {
        val plan = calculator.project(emptyList())

        assertTrue("nothing owed means nothing left unpayable", plan.clearsCompletely)
        assertTrue(plan.isDebtFree)
        assertTrue(plan.unpayable.isEmpty())
    }

    @Test
    fun `a zero balance debt with no payment is debt free, not unpayable`() {
        // The exact case reported: balance zero, payment zero. A debt that is
        // already cleared cannot fail to clear.
        val plan = calculator.project(
            listOf(
                debt("Fidelity Visa", balance = "0.00", payment = "0.00")
            )
        )

        assertTrue(plan.isDebtFree)
        assertTrue(plan.clearsCompletely)
        assertTrue("it must not be listed as never clearing", plan.unpayable.isEmpty())
    }

    @Test
    fun `a real balance with no payment really does never clear`() {
        // The behaviour worth keeping, so the fix does not silence a true
        // warning along with the false one.
        val plan = calculator.project(
            listOf(debt("Stuck", balance = "500.00", payment = "0.00"))
        )

        assertFalse(plan.clearsCompletely)
        assertFalse(plan.isDebtFree)
        assertEquals(1, plan.unpayable.size)
    }

    @Test
    fun `a cleared debt alongside a payable one does not poison the plan`() {
        val plan = calculator.project(
            listOf(
                debt("Cleared", balance = "0.00", payment = "0.00"),
                debt("Card", balance = "1000.00", payment = "100.00")
            )
        )

        assertTrue("the cleared one must not count against it", plan.clearsCompletely)
        assertFalse(plan.isDebtFree)
        assertTrue(plan.unpayable.isEmpty())
        assertNotNull(plan.debtFreeDate)
    }
}
