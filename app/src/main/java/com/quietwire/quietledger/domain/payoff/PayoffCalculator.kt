package com.quietwire.quietledger.domain.payoff

import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.domain.model.Debt
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debt payoff projections.
 *
 * This simulates month by month rather than using a closed-form estimate.
 * Simulating is what makes interest, payment rollover, and a final part
 * payment all fall out correctly instead of needing separate special cases.
 *
 * Three things the desktop version gets wrong, all fixed here:
 *
 *  1. It approximates the ceiling with `int(balance / payment + 0.99)`, which
 *     reports a month early whenever the last payment is a small remainder.
 *     Here a month is only counted when a payment actually happens.
 *  2. It advances dates by 30 days per month, so a year lands five days
 *     early and the error compounds. Here dates move by real calendar months.
 *  3. A debt with no payment produced -1 months, which was then used in
 *     arithmetic and silently corrupted every later projection. Here it is
 *     reported as unpayable and excluded from the totals.
 */
@Singleton
class PayoffCalculator @Inject constructor() {

    /**
     * @param extraMonthlyPayment anything above the scheduled payments, which
     *        goes to whichever debt is currently being targeted.
     * @param rollOverClearedPayments the snowball. **Off by default**, and
     *        that is a deliberate product decision rather than an oversight.
     *
     *        Rolling a cleared debt's payment into the next one assumes you
     *        will keep spending that money on debt. Plenty of people put it
     *        into savings, or investing, or simply stop paying it. Presenting a
     *        debt-free date that silently depends on a behaviour the user never
     *        agreed to is a forecast dressed up as a fact, and it produces
     *        figures that look wrong: a 9,985 balance at 541 a month reporting
     *        nine months rather than nineteen.
     *
     *        The honest default is to project what is actually happening now.
     *        Rollover is offered as a toggle, with the saving shown, so it is a
     *        choice someone makes rather than an assumption made for them.
     */
    fun project(
        debts: List<Debt>,
        startDate: LocalDate = LocalDate.now(),
        extraMonthlyPayment: Money = Money.ZERO,
        rollOverClearedPayments: Boolean = false
    ): PayoffPlan {
        val eligible = debts.filter { it.isOpen && it.currentBalance.isPositive }
        if (eligible.isEmpty()) return PayoffPlan.EMPTY

        // Snowball order: smallest balance first. Ties broken by name so the
        // ordering is stable rather than dependent on list order.
        val ordered = eligible.sortedWith(
            compareBy({ it.currentBalance.minorUnits }, { it.name })
        )

        val state = ordered.map { MutableDebtState(it) }

        var month = 0
        var clearedCount = 0

        while (state.any { !it.cleared } && month < MAX_MONTHS) {
            month++

            // Payments freed up by debts already gone.
            val freed = if (rollOverClearedPayments) {
                state.filter { it.cleared }
                    .fold(Money.ZERO) { acc, s -> acc + s.debt.monthlyPayment }
            } else {
                Money.ZERO
            }

            // Interest is charged before any payment lands.
            state.forEach { if (!it.cleared) it.applyInterest() }

            // Everything spare goes to the first debt still standing.
            val targetIndex = state.indexOfFirst { !it.cleared }
            var surplus = freed + extraMonthlyPayment

            for ((index, s) in state.withIndex()) {
                if (s.cleared) continue

                var available = s.debt.monthlyPayment
                if (index == targetIndex) {
                    available += surplus
                    surplus = Money.ZERO
                }
                if (available.isZero) continue

                val applied = if (available >= s.balance) s.balance else available
                s.pay(applied)

                // Do not overpay. Anything left rolls to the next debt this
                // same month rather than vanishing.
                val leftOver = available - applied
                if (leftOver.isPositive) surplus += leftOver

                if (s.balance.isZero && !s.cleared) {
                    clearedCount++
                    s.markCleared(order = clearedCount, atMonth = month)
                }
            }

            // Nothing moved this month, so nothing ever will.
            if (state.none { !it.cleared && it.movedThisMonth }) {
                if (state.any { !it.cleared }) break
            }
            state.forEach { it.movedThisMonth = false }
        }

        // Only computed when rollover is on, since without it the two numbers
        // are identical by definition and the extra simulation is wasted work.
        val projections = state.map { projected ->
            projected.toProjection(
                startDate,
                monthsAlone = if (rollOverClearedPayments) {
                    monthsOnOwnPayment(projected.debt)
                } else {
                    projected.monthsToPayoff
                }
            )
        }

        val allClear = projections.all { it.willClear }
        val lastMonth = projections.mapNotNull { it.monthsToPayoff }.maxOrNull()

        return PayoffPlan(
            projections = projections.sortedWith(
                compareBy(nullsLast()) { it.payoffOrder }
            ),
            monthsToDebtFree = if (allClear) lastMonth else null,
            debtFreeDate = if (allClear && lastMonth != null) {
                startDate.plusMonths(lastMonth.toLong())
            } else {
                null
            },
            totalPaid = projections.fold(Money.ZERO) { a, p -> a + p.totalPaid },
            totalInterest = projections.fold(Money.ZERO) { a, p -> a + p.interestPaid },
            startingMonthlyOutlay = ordered.fold(extraMonthlyPayment) { a, d ->
                a + d.monthlyPayment
            }
        )
    }

    fun compare(
        debts: List<Debt>,
        startDate: LocalDate = LocalDate.now(),
        extraMonthlyPayment: Money = Money.ZERO
    ) = PayoffComparison(
        snowball = project(debts, startDate, extraMonthlyPayment, rollOverClearedPayments = true),
        withoutRollover = project(debts, startDate, extraMonthlyPayment, rollOverClearedPayments = false)
    )

    private class MutableDebtState(val debt: Debt) {
        var balance: Money = debt.currentBalance
        var amountPaid: Money = Money.ZERO
        var interestPaid: Money = Money.ZERO
        var cleared: Boolean = false
        var payoffOrder: Int? = null
        var monthsToPayoff: Int? = null
        var movedThisMonth: Boolean = false

        fun applyInterest() {
            val bp = debt.aprBasisPoints ?: return
            if (bp <= 0 || !balance.isPositive) return

            // balance * apr / 10000 / 12, in integer cents, rounded half up.
            val interest = BigDecimal(balance.minorUnits)
                .multiply(BigDecimal(bp))
                .divide(BigDecimal(120_000), 0, RoundingMode.HALF_UP)
                .longValueExact()

            if (interest > 0) {
                val charge = Money.ofMinor(interest)
                balance += charge
                interestPaid += charge
            }
        }

        fun pay(amount: Money) {
            if (!amount.isPositive) return
            balance -= amount
            // Everything paid, including whatever covered interest. Splitting
            // it here is what previously double counted the interest.
            amountPaid += amount
            movedThisMonth = true
        }

        fun markCleared(order: Int, atMonth: Int) {
            cleared = true
            payoffOrder = order
            monthsToPayoff = atMonth
        }

        fun toProjection(startDate: LocalDate, monthsAlone: Int?): DebtProjection {
            val reason = when {
                cleared -> null
                debt.monthlyPayment.isZero -> UnpayableReason.NO_PAYMENT
                else -> UnpayableReason.INTEREST_EXCEEDS_PAYMENT
            }
            return DebtProjection(
                debt = debt,
                payoffOrder = payoffOrder,
                monthsToPayoff = monthsToPayoff,
                payoffDate = monthsToPayoff?.let { startDate.plusMonths(it.toLong()) },
                // Interest is charged even on a debt that never clears, so it
                // is reported either way.
                totalPaid = amountPaid,
                interestPaid = interestPaid,
                unpayableReason = reason,
                monthsOnOwnPayment = monthsAlone
            )
        }
    }


    /**
     * How long one debt takes on its own payment, with nothing rolling into it.
     *
     * Simulated rather than divided, so interest and the final part payment are
     * handled exactly as they are in the full plan. Dividing would disagree with
     * the main projection on any debt carrying an APR, and a self-contradicting
     * screen is worse than no explanation at all.
     */
    private fun monthsOnOwnPayment(debt: Debt): Int? {
        if (debt.monthlyPayment.isZero) return null

        val solo = MutableDebtState(debt)
        var month = 0
        while (!solo.cleared && month < MAX_MONTHS) {
            month++
            solo.applyInterest()

            // Clamped, exactly as the main loop does it. `pay` subtracts
            // whatever it is given, so handing it the full payment on the final
            // month drives the balance negative and it never reads as cleared.
            val payment = debt.monthlyPayment
            solo.pay(if (payment >= solo.balance) solo.balance else payment)

            if (solo.balance.isZero) solo.markCleared(1, month)
        }
        return solo.monthsToPayoff
    }

    companion object {
        /** Fifty years. Past this the projection is not telling you anything. */
        const val MAX_MONTHS = 600
    }
}
