package com.quietwire.quietledger.domain.model

import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.SavingsPurpose
import java.time.Instant

/** Money held, as opposed to money owed. */
data class SavingsAccount(
    val id: Long = 0,
    val name: String,
    val institution: String = "",
    val currentBalance: Money = Money.ZERO,
    val apyBasisPoints: Int? = null,
    val purpose: SavingsPurpose = SavingsPurpose.GENERAL,
    val goal: Money? = null,
    val closedAt: Instant? = null,
    val notes: String = "",
    val sortOrder: Int = 0,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH
) {
    val isOpen: Boolean get() = closedAt == null

    /** Annual interest at the recorded rate, if the balance sat still. */
    val projectedAnnualInterest: Money?
        get() = apyBasisPoints?.takeIf { it > 0 }?.let {
            Money.ofMinor(currentBalance.minorUnits * it / 10_000)
        }

    /**
     * Progress towards the goal, 0 to 1, or null when there is no goal.
     *
     * Capped at 1 so a bar cannot overflow, but [goalReached] is what the UI
     * should use to say something about exceeding it.
     */
    val goalProgress: Float?
        get() = goal?.takeIf { it.isPositive }?.let {
            (currentBalance.minorUnits.toFloat() / it.minorUnits.toFloat()).coerceIn(0f, 1f)
        }

    val goalReached: Boolean
        get() = goal?.let { currentBalance >= it } ?: false

    /** What is still needed, or null if there is no goal or it is met. */
    val remainingToGoal: Money?
        get() = goal?.takeIf { currentBalance < it }?.let { it - currentBalance }
}
