package com.quietwire.quietledger.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A debt: something with a balance that reduces towards zero.
 *
 * Amounts are whole cents. Timestamps are epoch milliseconds in UTC, so
 * sorting is a numeric comparison and there is no timezone ambiguity baked
 * into stored text.
 *
 * Instead of an is_paid flag this records when it was cleared, which answers
 * both "is it settled" and "when did that happen" with one column.
 */
@Entity(
    tableName = "debt",
    indices = [
        Index(value = ["closed_at"]),
        Index(value = ["category"])
    ]
)
data class DebtEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val category: String,

    @ColumnInfo(name = "current_balance_minor")
    val currentBalanceMinor: Long,

    /** What it started at, so progress can be shown. Null if never recorded. */
    @ColumnInfo(name = "original_balance_minor")
    val originalBalanceMinor: Long? = null,

    @ColumnInfo(name = "minimum_payment_minor")
    val minimumPaymentMinor: Long = 0,

    /** What is actually paid, which is often more than the minimum. */
    @ColumnInfo(name = "scheduled_payment_minor")
    val scheduledPaymentMinor: Long = 0,

    /** WEEKLY, BIWEEKLY, SEMIMONTHLY, MONTHLY, QUARTERLY or ANNUAL. */
    @ColumnInfo(name = "payment_frequency")
    val paymentFrequency: String,

    /**
     * Interest in basis points, so 1899 means 18.99 percent. An integer
     * because a rate is exact and should not drift. Null means not tracked,
     * which is how the desktop app behaves for everything.
     */
    /**
     * Marks the rate above as temporary, an introductory or balance-transfer
     * offer that will end.
     *
     * Only a flag, with no end date. Modelling the expiry would mean projecting
     * two rates across a boundary, and the honest version of that needs a date
     * the user has to supply and keep accurate. A flag says "this number will
     * change, do not trust the long projection", which is the part that
     * actually matters.
     */
    @ColumnInfo(name = "apr_is_promotional")
    val aprIsPromotional: Boolean = false,

    @ColumnInfo(name = "apr_basis_points")
    val aprBasisPoints: Int? = null,

    @ColumnInfo(name = "due_day_of_month")
    val dueDayOfMonth: Int? = null,

    /** Null while the debt is outstanding, set when it reaches zero. */
    @ColumnInfo(name = "closed_at")
    val closedAt: Long? = null,

    val notes: String = "",

    /** Manual ordering within a list, for when the user drags rows around. */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
