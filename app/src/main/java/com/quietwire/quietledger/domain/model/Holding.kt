package com.quietwire.quietledger.domain.model

import com.quietwire.quietledger.core.money.Money
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** One position in a brokerage account, as the broker last reported it. */
data class Holding(
    val id: Long = 0,
    val accountName: String,
    val symbol: String,
    val description: String = "",
    /** Share count. Null for cash, which has a value but no shares. */
    val quantity: BigDecimal? = null,
    val lastPrice: Money? = null,
    val currentValue: Money = Money.ZERO,
    /** Null when the broker does not report it. Fidelity sends "--". */
    val costBasis: Money? = null,
    val gainLoss: Money? = null,
    val isCash: Boolean = false,
    val type: String = "",
    val asOf: Instant = Instant.EPOCH
) {
    /**
     * Gain or loss as a fraction of cost, or null without a cost basis.
     *
     * Derived rather than taken from the file's percentage column, so it cannot
     * disagree with the money figures shown beside it.
     */
    val gainLossFraction: Double?
        get() {
            val basis = costBasis?.takeIf { it.isPositive } ?: return null
            val change = gainLoss ?: return null
            return change.minorUnits.toDouble() / basis.minorUnits.toDouble()
        }

    val isUp: Boolean get() = gainLoss?.isPositive == true
    val isDown: Boolean get() = gainLoss?.isNegative == true

    /** Shares formatted without trailing noise from the stored scale. */
    val quantityLabel: String?
        get() = quantity?.stripTrailingZeros()?.toPlainString()

    /**
     * How stale the figures are.
     *
     * Worth surfacing: a weekly export means the value shown is right for the
     * day it was produced and increasingly approximate afterwards, which is a
     * different claim from a live price.
     */
    fun ageInDays(now: Instant = Instant.now()): Long =
        Duration.between(asOf, now).toDays().coerceAtLeast(0)
}
