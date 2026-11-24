package com.quietwire.quietledger.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recurring obligation with no balance: rent, insurance, utilities,
 * subscriptions, automatic transfers to savings.
 *
 * These never get paid off, so they have no balance and cannot appear in a
 * payoff projection. Keeping them apart from [DebtEntity] is what removes the
 * category filtering that the desktop app needs on every query.
 */
@Entity(
    tableName = "recurring_expense",
    indices = [
        Index(value = ["is_active"]),
        Index(value = ["category"])
    ]
)
data class RecurringExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val category: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    /** Stored rather than pre-converted, so the monthly figure is derived. */
    val frequency: String,

    @ColumnInfo(name = "due_day_of_month")
    val dueDayOfMonth: Int? = null,

    /** Kept rather than deleted when cancelled, so history stays intact. */
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,

    val notes: String = "",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
