package com.quietwire.quietledger.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line from a brokerage positions export.
 *
 * Holdings are **not** edited by hand. A positions file is a snapshot of an
 * account at a moment, so each import replaces what was there rather than
 * merging into it. Merging would leave a sold position sitting in the list for
 * ever, quietly inflating net worth.
 *
 * That also solves the valuation problem. The desktop app fetches live prices
 * with yfinance; this app has no network permission, and the export already
 * carries the value the broker calculated. [asOf] records when, so a stale
 * figure can be labelled rather than silently presented as current.
 */
@Entity(tableName = "holding")
data class HoldingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** As it appears in the export, with no account number. */
    @ColumnInfo(name = "account_name")
    val accountName: String,

    val symbol: String,

    val description: String = "",

    /**
     * Share count in millionths, so 123.456 is stored as 123_456_000.
     *
     * A scaled integer for the same reason money is in cents. Fractional shares
     * from reinvested dividends run to several decimal places, and a float
     * quantity multiplied by a float price is how a portfolio total ends up
     * disagreeing with itself.
     *
     * Null for cash, which has a value but no share count.
     */
    @ColumnInfo(name = "quantity_micros")
    val quantityMicros: Long? = null,

    @ColumnInfo(name = "last_price_minor")
    val lastPriceMinor: Long? = null,

    @ColumnInfo(name = "current_value_minor")
    val currentValueMinor: Long = 0,

    /** Null when the broker does not report it, which Fidelity sends as "--". */
    @ColumnInfo(name = "cost_basis_minor")
    val costBasisMinor: Long? = null,

    @ColumnInfo(name = "gain_loss_minor")
    val gainLossMinor: Long? = null,

    /** Core or money market position, such as SPAXX. Cash, not a holding. */
    @ColumnInfo(name = "is_cash")
    val isCash: Boolean = false,

    /** Fidelity's own classification, kept as sent. */
    val type: String = "",

    /**
     * When the broker produced these figures, from the export footer.
     *
     * The reason a positions list can be trusted without a network call, and
     * the reason the screen can say how old it is.
     */
    @ColumnInfo(name = "as_of")
    val asOf: Long,

    @ColumnInfo(name = "imported_at")
    val importedAt: Long
) {
    companion object {
        const val QUANTITY_SCALE = 1_000_000L
    }
}
