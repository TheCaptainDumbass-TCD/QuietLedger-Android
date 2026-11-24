package com.quietwire.quietledger.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How often an amount is paid.
 *
 * The desktop app stores a weekly amount and a monthly amount in separate
 * columns and converts with `weekly * 4 + monthly`. Four weeks is not a
 * month. That counts 48 weeks in a year instead of 52, understating anything
 * paid weekly by 7.7 percent, which on a 100 dollar weekly payment is 400
 * dollars a year of payments that quietly do not exist.
 *
 * Storing the frequency and converting properly avoids the whole class of
 * problem, and handles fortnightly and quarterly for free.
 */
enum class Frequency(val perYear: Int, val label: String) {
    WEEKLY(52, "Weekly"),
    BIWEEKLY(26, "Every 2 weeks"),
    SEMIMONTHLY(24, "Twice a month"),
    MONTHLY(12, "Monthly"),
    QUARTERLY(4, "Quarterly"),
    ANNUAL(1, "Yearly");

    /** The amount normalised to a month, rounded to the nearest cent. */
    fun monthlyEquivalent(amount: Money): Money {
        if (this == MONTHLY) return amount
        val monthly = BigDecimal(amount.minorUnits)
            .multiply(BigDecimal(perYear))
            .divide(BigDecimal(12), 0, RoundingMode.HALF_UP)
        return Money.ofMinor(monthly.longValueExact())
    }

    /** The amount normalised to a year. Exact, no rounding needed. */
    fun annualEquivalent(amount: Money): Money = amount * perYear

    companion object {
        fun fromStored(value: String): Frequency =
            entries.firstOrNull { it.name == value } ?: MONTHLY
    }
}
