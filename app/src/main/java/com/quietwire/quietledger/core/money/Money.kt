package com.quietwire.quietledger.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * An exact monetary amount, held as whole minor units (cents).
 *
 * Money is never a floating point number here. A double cannot represent 0.10
 * exactly, so sums drift and equality comparisons stop meaning what they look
 * like they mean. The desktop app stores every amount as a SQLite REAL, and
 * after twelve subtractions a balance that should be zero comes out as
 * -8.5e-14. Small enough to hide, big enough to make "is this paid off" the
 * wrong answer.
 *
 * Being a value class, this compiles down to a plain Long at runtime, so the
 * safety costs nothing. It also stops you passing dollars where cents are
 * expected, which is the other classic way money code goes wrong.
 */
@JvmInline
value class Money private constructor(val minorUnits: Long) : Comparable<Money> {

    val isZero: Boolean get() = minorUnits == 0L
    val isPositive: Boolean get() = minorUnits > 0
    val isNegative: Boolean get() = minorUnits < 0

    operator fun plus(other: Money) = Money(Math.addExact(minorUnits, other.minorUnits))

    operator fun minus(other: Money) = Money(Math.subtractExact(minorUnits, other.minorUnits))

    operator fun times(factor: Int) = Money(Math.multiplyExact(minorUnits, factor.toLong()))

    operator fun times(factor: Long) = Money(Math.multiplyExact(minorUnits, factor))

    operator fun unaryMinus() = Money(-minorUnits)

    val absoluteValue: Money get() = Money(minorUnits.absoluteValue)

    /**
     * Division has to round, so it is explicit rather than an operator. The
     * default rounds half away from zero, which is what people expect when
     * splitting a bill.
     */
    fun divide(divisor: Int, rounding: RoundingMode = RoundingMode.HALF_UP): Money {
        require(divisor != 0) { "Cannot divide money by zero" }
        val result = BigDecimal(minorUnits)
            .divide(BigDecimal(divisor), 0, rounding)
        return Money(result.longValueExact())
    }

    /**
     * How many whole payments of [payment] it takes to clear this amount.
     * Returns null when the payment cannot ever clear it.
     */
    fun paymentsToClear(payment: Money): Int? {
        if (minorUnits <= 0) return 0
        if (payment.minorUnits <= 0) return null
        // Integer ceiling, no floating point, no "+ 0.99" approximation.
        val count = (minorUnits + payment.minorUnits - 1) / payment.minorUnits
        return count.toInt()
    }

    fun toBigDecimal(): BigDecimal = BigDecimal(minorUnits).movePointLeft(2)

    /** Only for display and for interop with systems that insist on doubles. */
    fun toDouble(): Double = minorUnits / 100.0

    fun format(locale: Locale = Locale.US): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance("USD")
        }.format(toBigDecimal())

    /** "1234.56", suitable for putting in a text field for editing. */
    fun toPlainString(): String = toBigDecimal().toPlainString()

    override fun compareTo(other: Money): Int = minorUnits.compareTo(other.minorUnits)

    override fun toString(): String = "Money(${toPlainString()})"

    companion object {
        val ZERO = Money(0)

        fun ofMinor(minorUnits: Long) = Money(minorUnits)

        fun ofMajor(major: Long) = Money(Math.multiplyExact(major, 100L))

        fun of(value: BigDecimal, rounding: RoundingMode = RoundingMode.HALF_UP) =
            Money(value.setScale(2, rounding).movePointRight(2).longValueExact())

        /**
         * For importing from the desktop app, which stored amounts as doubles.
         * Rounds to the nearest cent, which is the best that can be recovered.
         */
        fun fromDouble(value: Double, rounding: RoundingMode = RoundingMode.HALF_UP) =
            of(BigDecimal(value.toString()), rounding)

        /**
         * Parses user input and broker exports. Accepts "$1,234.56", "1234.56",
         * "-$0.08", "+3.40", "(12.34)". Returns null for anything else rather
         * than guessing.
         */
        fun parseOrNull(raw: String): Money? {
            var text = raw.trim()
            if (text.isEmpty()) return null

            var negative = false
            if (text.startsWith("(") && text.endsWith(")")) {
                negative = true
                text = text.substring(1, text.length - 1)
            }

            text = text.replace("$", "")
                .replace(",", "")
                .replace(" ", "")
                .replace("\u00A0", "")

            if (text.startsWith("+")) text = text.substring(1)
            if (text.startsWith("-")) {
                negative = !negative
                text = text.substring(1)
            }

            if (text.isEmpty()) return null
            if (!text.all { it.isDigit() || it == '.' }) return null
            if (text.count { it == '.' } > 1) return null

            val decimal = text.toBigDecimalOrNull() ?: return null
            val money = of(decimal)
            return if (negative) -money else money
        }
    }
}
