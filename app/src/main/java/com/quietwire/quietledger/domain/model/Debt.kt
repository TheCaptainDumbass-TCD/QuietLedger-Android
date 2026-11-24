package com.quietwire.quietledger.domain.model

import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.DebtCategory
import java.time.Instant

/**
 * A debt as the rest of the app sees it: real types, no raw longs, no
 * strings standing in for enums.
 */
data class Debt(
    val id: Long = 0,
    val name: String,
    val category: DebtCategory,
    val currentBalance: Money,
    val originalBalance: Money? = null,
    val minimumPayment: Money = Money.ZERO,
    val scheduledPayment: Money = Money.ZERO,
    val paymentFrequency: Frequency = Frequency.MONTHLY,
    /** 1899 means 18.99 percent. Null means interest is not tracked. */
    val aprBasisPoints: Int? = null,
    /** The rate above is an introductory offer that will end. */
    val aprIsPromotional: Boolean = false,
    val dueDayOfMonth: Int? = null,
    val closedAt: Instant? = null,
    val notes: String = "",
    val sortOrder: Int = 0,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH
) {
    val isOpen: Boolean get() = closedAt == null

    val isSnowballEligible: Boolean get() = category.snowballEligible && isOpen

    /** What actually gets paid each month, however the schedule is expressed. */
    val monthlyPayment: Money get() = paymentFrequency.monthlyEquivalent(effectivePayment)

    /**
     * The scheduled payment if one is set, otherwise the minimum. Paying only
     * the minimum is a choice, but paying nothing is usually an omission.
     */
    val effectivePayment: Money
        get() = if (scheduledPayment.isPositive) scheduledPayment else minimumPayment

    val monthlyInterestRate: Double?
        get() = aprBasisPoints?.let { it / 10_000.0 / 12.0 }

    /** Progress from the original balance, or null if there is nothing to compare to. */
    val fractionPaid: Float?
        get() {
            val original = originalBalance ?: return null
            if (!original.isPositive) return null
            val paid = original - currentBalance
            return (paid.minorUnits.toFloat() / original.minorUnits.toFloat())
                .coerceIn(0f, 1f)
        }
}
