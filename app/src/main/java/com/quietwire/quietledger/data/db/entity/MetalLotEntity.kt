package com.quietwire.quietledger.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line from a bullion invoice: a quantity of one product, bought once.
 *
 * Stored as purchase lots rather than as a running total per metal, because the
 * invoice is the record and a lot keeps its own date, price and premium. Summing
 * into "200 oz of silver" would discard the cost basis, which is the thing
 * actually worth tracking for a holding that is not being traded.
 *
 * Valued at **cost**, not at market. There is no spot price feed and no network
 * permission, and for a static holding what was paid is both the honest figure
 * and the useful one. [spotPriceMinor] is what the vendor printed on the day,
 * which is the only way a premium over spot can be known at all.
 */
@Entity(tableName = "metal_lot")
data class MetalLotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val vendor: String,

    /** Invoice number, so a figure can be traced back to the PDF. */
    @ColumnInfo(name = "order_number")
    val orderNumber: String,

    @ColumnInfo(name = "purchased_at")
    val purchasedAt: Long,

    val description: String,

    /** GOLD or SILVER. */
    val metal: String,

    /** Units bought, in thousandths to allow a fractional count. */
    @ColumnInfo(name = "quantity_milli")
    val quantityMilli: Long,

    /**
     * Weight of one unit in micro troy ounces, so a 1/10 oz coin is 100_000.
     *
     * Scaled integers throughout. Kilo bars and gram pieces convert to troy
     * ounces on the way in, so everything is comparable without carrying a
     * unit around.
     */
    @ColumnInfo(name = "unit_weight_micro_oz")
    val unitWeightMicroOz: Long,

    /** The unit as the invoice wrote it, kept for display. */
    @ColumnInfo(name = "weight_label")
    val weightLabel: String = "",

    @ColumnInfo(name = "unit_price_minor")
    val unitPriceMinor: Long,

    /** Line total before shipping. */
    @ColumnInfo(name = "extended_price_minor")
    val extendedPriceMinor: Long,

    /**
     * This lot's share of the order's shipping and handling.
     *
     * Allocated pro rata by line value, with the rounding remainder given to
     * the largest line so the parts sum to the invoice exactly. Shipping is
     * part of what was paid, so leaving it out would understate the cost basis.
     */
    @ColumnInfo(name = "shipping_minor")
    val shippingMinor: Long = 0,

    /** Spot price per troy ounce as printed on the invoice, if it was. */
    @ColumnInfo(name = "spot_price_minor")
    val spotPriceMinor: Long? = null,

    @ColumnInfo(name = "source_file")
    val sourceFile: String = "",

    val notes: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    companion object {
        const val QUANTITY_SCALE = 1_000L
        const val WEIGHT_SCALE = 1_000_000L
    }
}
