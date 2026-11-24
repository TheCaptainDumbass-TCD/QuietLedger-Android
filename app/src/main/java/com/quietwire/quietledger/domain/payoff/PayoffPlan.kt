package com.quietwire.quietledger.domain.payoff

import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.domain.model.Debt
import java.time.LocalDate

/** Why a debt can never be cleared on the current plan. */
enum class UnpayableReason(val explanation: String) {
    NO_PAYMENT("No payment is set, so the balance never moves."),
    INTEREST_EXCEEDS_PAYMENT("The interest each month is more than the payment, so the balance grows.")
}

data class DebtProjection(
    val debt: Debt,
    /** 1 for the debt cleared first. Null if it never clears. */
    val payoffOrder: Int?,
    val monthsToPayoff: Int?,
    val payoffDate: LocalDate?,
    /**
     * Everything that leaves your pocket for this debt, interest included.
     *
     * Stored rather than derived, because the reverse caused a real bug: when
     * principal was the stored field, the payment covering interest was
     * recorded as principal, so adding interest on top counted it twice and
     * the reported total was too high by exactly the interest.
     */
    val totalPaid: Money,
    val interestPaid: Money,
    val unpayableReason: UnpayableReason? = null,
    /**
     * How long this debt would take on its own payment, with nothing rolling
     * into it.
     *
     * Carried so the screen can explain itself. A snowball plan can report a
     * figure far shorter than balance divided by payment, which looks like an
     * error until you know that a cleared debt's payment is now funding this
     * one. Showing both numbers turns "that cannot be right" into "ah, of
     * course". Null when it would never clear on its own.
     */
    val monthsOnOwnPayment: Int? = null
) {
    /**
     * How much of the balance actually went away. Negative if interest
     * outpaced the payments, which is the honest answer when a balance grows.
     */
    val principalPaid: Money get() = totalPaid - interestPaid

    val willClear: Boolean get() = payoffDate != null

    /** True when rollover is doing meaningful work on this debt. */
    val acceleratedByRollover: Boolean
        get() {
            val own = monthsOnOwnPayment ?: return monthsToPayoff != null
            val actual = monthsToPayoff ?: return false
            return own > actual
        }

    /** Months saved on this debt by other debts clearing first. */
    val monthsSavedByRollover: Int?
        get() {
            val own = monthsOnOwnPayment ?: return null
            val actual = monthsToPayoff ?: return null
            return (own - actual).takeIf { it > 0 }
        }
}

data class PayoffPlan(
    val projections: List<DebtProjection>,
    val monthsToDebtFree: Int?,
    val debtFreeDate: LocalDate?,
    /** Everything paid across every debt, interest included. */
    val totalPaid: Money,
    val totalInterest: Money,
    /** What leaves your pocket each month at the start of the plan. */
    val startingMonthlyOutlay: Money
) {
    /** The part of the total that reduced balances rather than paying interest. */
    val totalPrincipal: Money get() = totalPaid - totalInterest

    /**
     * Nothing is left that cannot be paid off.
     *
     * True when there is no debt at all, which is the point this used to get
     * backwards. It was `debtFreeDate != null`, and a plan with nothing in it
     * has no date, so owing nothing was reported as "Not on track" in red. The
     * best possible position was displayed as a failure.
     */
    val clearsCompletely: Boolean get() = unpayable.isEmpty()

    /** No debt with a balance, so there is nothing to project. */
    val isDebtFree: Boolean get() = projections.isEmpty()

    val unpayable: List<DebtProjection> get() = projections.filter { !it.willClear }

    companion object {
        val EMPTY = PayoffPlan(
            projections = emptyList(),
            monthsToDebtFree = 0,
            debtFreeDate = null,
            totalPaid = Money.ZERO,
            totalInterest = Money.ZERO,
            startingMonthlyOutlay = Money.ZERO
        )
    }
}

/**
 * Side by side comparison so the benefit of rolling payments over is visible
 * rather than asserted.
 */
data class PayoffComparison(
    val snowball: PayoffPlan,
    val withoutRollover: PayoffPlan
) {
    val monthsSaved: Int?
        get() {
            val a = snowball.monthsToDebtFree ?: return null
            val b = withoutRollover.monthsToDebtFree ?: return null
            return (b - a).coerceAtLeast(0)
        }

    val interestSaved: Money
        get() = (withoutRollover.totalInterest - snowball.totalInterest)
            .let { if (it.isNegative) Money.ZERO else it }
}
