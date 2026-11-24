package com.quietwire.quietledger.domain.model

import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.RecurringExpenseCategory
import java.time.Instant

/** A recurring obligation with no balance. Rent, insurance, subscriptions. */
data class RecurringExpense(
    val id: Long = 0,
    val name: String,
    val category: RecurringExpenseCategory,
    val amount: Money,
    val frequency: Frequency = Frequency.MONTHLY,
    val dueDayOfMonth: Int? = null,
    val isActive: Boolean = true,
    val endedAt: Instant? = null,
    val notes: String = "",
    val sortOrder: Int = 0,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH
) {
    val monthlyAmount: Money get() = frequency.monthlyEquivalent(amount)
    val annualAmount: Money get() = frequency.annualEquivalent(amount)
}
