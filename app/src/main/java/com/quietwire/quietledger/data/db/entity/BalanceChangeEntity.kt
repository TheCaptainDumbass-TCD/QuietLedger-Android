package com.quietwire.quietledger.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An append-only record of every amount that changed.
 *
 * The desktop app overwrites balances in place, so the previous value is gone
 * the moment you save. Its Analytics tab works around that by writing a
 * nightly snapshot, which means history only exists for days the app happened
 * to be open.
 *
 * Writing a row here on every change costs nothing at the point of editing,
 * requires no extra work from the user, and gives real provenance: what the
 * number was, what it became, when, and why. Charts then come from actual
 * history rather than from sampling.
 *
 * Nothing in this table is ever updated or deleted.
 */
@Entity(
    tableName = "balance_change",
    indices = [
        Index(value = ["subject_type", "subject_id", "occurred_at"]),
        Index(value = ["occurred_at"])
    ]
)
data class BalanceChangeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** DEBT, RECURRING_EXPENSE, CASH_ACCOUNT, HOLDING or PRECIOUS_METAL. */
    @ColumnInfo(name = "subject_type")
    val subjectType: String,

    @ColumnInfo(name = "subject_id")
    val subjectId: Long,

    /** Which amount moved, for example "balance" or "scheduled_payment". */
    val field: String,

    /** Null when the record was first created and had no prior value. */
    @ColumnInfo(name = "previous_minor")
    val previousMinor: Long?,

    @ColumnInfo(name = "new_minor")
    val newMinor: Long,

    /**
     * Stored rather than derived. Keeping it means a chart can sum deltas
     * without reading every prior row, and it survives a subject being
     * deleted.
     */
    @ColumnInfo(name = "delta_minor")
    val deltaMinor: Long,

    /** MANUAL, INITIAL, IMPORT, PAYMENT or CORRECTION. */
    val source: String,

    /** Kept so history reads sensibly after something is renamed or removed. */
    @ColumnInfo(name = "subject_name")
    val subjectName: String,

    val note: String? = null,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long
)
