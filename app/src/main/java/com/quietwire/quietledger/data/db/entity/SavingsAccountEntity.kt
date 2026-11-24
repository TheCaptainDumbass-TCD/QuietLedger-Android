package com.quietwire.quietledger.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A savings account, held as an asset.
 *
 * The balance is stored directly, because unlike an investment it is simply
 * known. No price lookup, no valuation, no as-of date: the number the bank shows
 * is the number. That is what makes savings the straightforward half of net
 * worth and investments the awkward half.
 *
 * APY is recorded for interest projection later. It is not applied to the
 * balance automatically, because this app does not invent transactions that did
 * not happen; the balance changes when the user says it changed.
 */
@Entity(tableName = "savings_account")
data class SavingsAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /** Bank or provider. Free text, never an account number. */
    val institution: String = "",

    @ColumnInfo(name = "current_balance_minor")
    val currentBalanceMinor: Long = 0,

    /**
     * Annual percentage yield in basis points, so 4.25% is 425.
     *
     * Basis points rather than a decimal for the same reason money is in cents:
     * a rate stored as a float compares and sums unreliably.
     */
    @ColumnInfo(name = "apy_basis_points")
    val apyBasisPoints: Int? = null,

    /**
     * What this account is for, which changes how it should be read.
     *
     * An emergency fund at three months of expenses means something different
     * from a holiday fund at the same balance.
     */
    val purpose: String = "GENERAL",

    /** Optional target, for showing progress towards a goal. */
    @ColumnInfo(name = "goal_minor")
    val goalMinor: Long? = null,

    @ColumnInfo(name = "closed_at")
    val closedAt: Long? = null,

    val notes: String = "",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
