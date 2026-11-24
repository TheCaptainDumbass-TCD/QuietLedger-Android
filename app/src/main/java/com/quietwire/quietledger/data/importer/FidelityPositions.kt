package com.quietwire.quietledger.data.importer

import java.time.LocalDateTime

/**
 * One holding from a Fidelity positions export.
 *
 * Most numeric fields are nullable on purpose. Fidelity writes "--" when a
 * value is unavailable, and leaves cells empty for money market rows, so a
 * missing cost basis is normal rather than an error. Storing zero instead
 * would quietly turn "unknown" into "no gain".
 */
data class FidelityPosition(
    val accountName: String,
    val symbol: String,
    val description: String,
    val quantity: Double?,
    val lastPrice: Double?,
    val currentValue: Double?,
    val totalGainLossDollar: Double?,
    val costBasisTotal: Double?,
    val averageCostBasis: Double?,
    val type: String,
    /**
     * True for core or money market rows such as SPAXX, which have a value but
     * no quantity or price. These are cash, not holdings.
     */
    val isCash: Boolean
)

/** A row present in the file that deliberately did not become a position. */
data class SkippedRow(val label: String, val reason: String)

data class FidelityImport(
    val positions: List<FidelityPosition>,
    val skipped: List<SkippedRow>,
    /** From the "Date downloaded" footer. This is the as-of date for prices. */
    val downloadedAt: LocalDateTime?
) {
    val cashTotal: Double
        get() = positions.filter { it.isCash }.sumOf { it.currentValue ?: 0.0 }

    val holdings: List<FidelityPosition>
        get() = positions.filterNot { it.isCash }
}

class FidelityParseException(message: String) : Exception(message)
