package com.quietwire.quietledger.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A spot price the user typed in, per troy ounce.
 *
 * Entered rather than fetched. The app holds no `INTERNET` permission, so it
 * structurally cannot send financial data anywhere; acquiring that permission
 * to look up three numbers would trade an OS-enforced guarantee for a promise.
 * For a holding that is not being traded, typing three figures occasionally is
 * the better side of that bargain.
 *
 * [enteredAt] is what keeps the resulting valuation honest. A market value is
 * only as current as the price behind it, and the screen says whose price it is
 * and when it was given, so it never reads as a live quote.
 */
@Entity(tableName = "metal_spot_price")
data class MetalSpotPriceEntity(
    /** GOLD or SILVER. One row per metal, so the metal is the key. */
    @PrimaryKey
    val metal: String,

    @ColumnInfo(name = "price_per_oz_minor")
    val pricePerOzMinor: Long,

    @ColumnInfo(name = "entered_at")
    val enteredAt: Long
)
