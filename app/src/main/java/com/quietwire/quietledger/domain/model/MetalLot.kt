package com.quietwire.quietledger.domain.model

import com.quietwire.quietledger.core.money.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * A metal held as bullion. Gold and silver only.
 *
 * Both are quoted and weighed in troy ounces, so there is one unit everywhere
 * and nothing to convert.
 *
 * Copper was tracked and then removed. It is quoted per pound rather than per
 * troy ounce, which needed a conversion; the invoices carried a placeholder
 * spot price of $0.10 that produced nonsense premiums; and the holding was
 * novelty Christmas rounds whose value is the minting rather than the metal,
 * so valuing them at melt showed an $44 loss that does not exist in any real
 * sense. Three special cases and a misleading number, for fifty dollars of
 * decorative copper. Not worth it.
 */
enum class Metal(val label: String) {
    GOLD("Gold"),
    SILVER("Silver");

    companion object {
        fun fromStored(value: String): Metal? = entries.firstOrNull { it.name == value }
    }

    /** Value of a holding at a price per troy ounce. */
    fun valueOf(ounces: BigDecimal, pricePerOunce: Money): Money = Money.ofMinor(
        (BigDecimal(pricePerOunce.minorUnits) * ounces)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    )
}

/** One bullion purchase, as the invoice recorded it. */
data class MetalLot(
    val id: Long = 0,
    val vendor: String,
    val orderNumber: String,
    val purchasedAt: Instant,
    val description: String,
    val metal: Metal,
    val quantity: BigDecimal,
    val unitWeightOz: BigDecimal,
    val weightLabel: String = "",
    val unitPrice: Money = Money.ZERO,
    val extendedPrice: Money = Money.ZERO,
    val shipping: Money = Money.ZERO,
    val spotPrice: Money? = null,
    val sourceFile: String = ""
) {
    /** Troy ounces of metal in this lot. */
    /**
     * The exact weight of this lot. Deliberately not rounded.
     *
     * This used to round to four decimals, and the error accumulated. 28 lots
     * of 0.032151 oz each round up to 0.0322, so the
     * total gained 0.0012 oz and the valuation gained five dollars against the
     * same data read on the server.
     *
     * Rounding belongs at the point of display, once, not at every step of a
     * sum. [ouncesLabel] does it for the screen.
     */
    val totalOunces: BigDecimal
        get() = quantity * unitWeightOz

    /** Everything paid for this lot, including its share of shipping. */
    val totalCost: Money get() = extendedPrice + shipping

    /**
     * How far above the metal's melt value this was bought, as a fraction.
     *
     * Only knowable because the invoice printed the spot price on the day; it
     * cannot be reconstructed afterwards. Null without one, rather than zero,
     * since zero would claim it was bought at melt.
     */
    val premiumOverSpot: Double?
        get() = rawPremiumOverSpot?.takeIf { it <= MAX_CREDIBLE_PREMIUM }

    /**
     * The premium as the invoice's own figures give it, credible or not.
     *
     * Separated so the UI can tell "no spot recorded" apart from "a spot was
     * recorded and it cannot be right".
     */
    private val rawPremiumOverSpot: Double?
        get() {
            val spot = spotPrice?.takeIf { it.isPositive } ?: return null
            val melt = BigDecimal(spot.minorUnits) * unitWeightOz
            if (melt <= BigDecimal.ZERO) return null
            return (BigDecimal(unitPrice.minorUnits) - melt).toDouble() / melt.toDouble()
        }

    /**
     * The invoice printed a spot price that cannot be right.
     *
     * SD Bullion prints `Copper: $0.10` on every invoice, where copper actually
     * trades around $0.45 a troy ounce. A $2.29 round then computes to 2190%
     * over melt instead of a real 408%, which is a number that looks like a
     * bug and discredits every other figure beside it.
     *
     * Ten times melt is the bound. Novelty copper rounds genuinely sell at four
     * or five times melt, so the threshold has to clear that; nothing in
     * bullion sells at ten times, so beyond it the spot figure is the thing at
     * fault rather than the price paid.
     */
    val spotLooksUnreliable: Boolean
        get() = rawPremiumOverSpot?.let { it > MAX_CREDIBLE_PREMIUM } ?: false

    /**
     * Premium over a spot price supplied from outside, rather than the invoice.
     *
     * Used when the invoice's own figure cannot be right. It answers a slightly
     * different question. How far above *today's* melt value this was bought,
     * rather than the day's premium at the time, so the UI has to say which it
     * is showing. Still worth having: for copper the invoice says $0.10 where
     * the metal was nearer $0.275, and silence is less useful than a true
     * figure against a stated reference.
     */
    fun premiumOver(spotPerOunce: Money): Double? {
        if (!spotPerOunce.isPositive) return null
        val melt = BigDecimal(spotPerOunce.minorUnits) * unitWeightOz
        if (melt <= BigDecimal.ZERO) return null
        return (BigDecimal(unitPrice.minorUnits) - melt).toDouble() / melt.toDouble()
    }

    /** Rounded for the screen only, never for arithmetic. */
    val ouncesLabel: String
        get() = totalOunces.setScale(4, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    companion object {
        /** Ten times melt. Above this the quoted spot is wrong, not the price. */
        const val MAX_CREDIBLE_PREMIUM = 10.0
    }

    val quantityLabel: String
        get() = quantity.stripTrailingZeros().toPlainString()
}

/**
 * A spot price as the user entered it, in the unit that metal is quoted in.
 *
 * Stored as quoted rather than normalised to per-ounce. Normalising means
 * rounding a sub-dollar figure to the cent, and for copper at about 45 cents an
 * ounce that loses over 1% of the valuation. It also means the editor can show
 * exactly what was typed.
 */
data class SpotPrice(
    val metal: Metal,
    val quotedPrice: Money,
    val enteredAt: Instant
) {
    /** Stored per troy ounce, so this is simply the price. */
    val perTroyOunce: Money get() = quotedPrice

    fun ageInDays(now: Instant = Instant.now()): Long =
        java.time.Duration.between(enteredAt, now).toDays().coerceAtLeast(0)
}

/** A metal's holdings rolled up, for the summary rows. */
data class MetalSummary(
    val metal: Metal,
    val ounces: BigDecimal,
    val cost: Money,
    val lots: Int,
    /** Null until a price has been entered for this metal. */
    val spot: SpotPrice? = null
) {
    /**
     * What the metal is worth at the entered price, or null without one.
     *
     * Null rather than falling back to cost, because "worth what I paid" is a
     * claim about the market that nobody has made. The UI shows cost on its own
     * in that case.
     */
    val marketValue: Money?
        get() = spot?.let { metal.valueOf(ounces, it.quotedPrice) }

    /** Gain or loss against everything paid, including shipping. */
    val gain: Money? get() = marketValue?.let { it - cost }

    val gainFraction: Double?
        get() {
            val value = marketValue ?: return null
            if (!cost.isPositive) return null
            return (value.minorUnits - cost.minorUnits).toDouble() / cost.minorUnits.toDouble()
        }

    /** Average paid per troy ounce, including allocated shipping. */
    val averageCostPerOunce: Money?
        get() = ounces.takeIf { it > BigDecimal.ZERO }?.let {
            Money.ofMinor(
                BigDecimal(cost.minorUnits)
                    .divide(it, 0, RoundingMode.HALF_UP)
                    .toLong()
            )
        }

    /** Rounded for the screen only. The sum behind it is exact. */
    val ouncesLabel: String
        get() = ounces.setScale(4, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}
