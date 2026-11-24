package com.quietwire.quietledger.data.repository

import androidx.room.withTransaction
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.db.entity.HoldingEntity
import com.quietwire.quietledger.data.importer.FidelityImport
import com.quietwire.quietledger.data.importer.FidelityPosition
import com.quietwire.quietledger.domain.model.Holding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What an import actually did, so the UI can report it rather than guess. */
data class HoldingsImportResult(
    val accountName: String,
    val positions: Int,
    val cashPositions: Int,
    val total: Money,
    val asOf: Instant,
    val skipped: List<String>,
    val removed: Int
)

/**
 * Brokerage holdings, populated only from a positions export.
 *
 * There is no add or edit. A positions file is the broker's own statement of
 * what is held and what it is worth, and hand-editing it would mean the total
 * no longer corresponds to anything. The weekly export is the source of truth,
 * which is also what lets this work with no network permission.
 */
@Singleton
class HoldingsRepository @Inject constructor(
    private val databases: DatabaseHolder
) {

    /**
     * Resolves the database per subscription and follows it across a swap.
     *
     * Not `flow { emitAll(block(databases.require())) }`: that throws while
     * locked, and the throw used to be caught in the ViewModel and rendered as
     * "you have no data". Waiting for the database instead means a momentary
     * lock produces no emission rather than a false empty state, and unlocking
     * re-subscribes automatically.
     */
    private fun <T> fromDatabase(block: (QuietLedgerDatabase) -> Flow<T>): Flow<T> =
        databases.observe().filterNotNull().flatMapLatest { block(it) }

    fun observeAll(): Flow<List<Holding>> =
        fromDatabase { it.holdingDao().observeAll() }
            .map { list -> list.map { h -> h.toDomain() } }

    /** Total value, which is the investments contribution to net worth. */
    fun observeTotal(): Flow<Money> =
        fromDatabase { it.holdingDao().observeTotal() }.map { Money.ofMinor(it) }

    /** When the broker produced the figures, so staleness can be shown. */
    fun observeAsOf(): Flow<Instant?> =
        fromDatabase { it.holdingDao().observeAsOf() }
            .map { millis -> millis?.let { Instant.ofEpochMilli(it) } }

    /**
     * Replaces the holdings for whichever account the file describes.
     *
     * Scoped to the account rather than wiping the table, so importing one
     * account's export does not silently delete another's.
     */
    suspend fun applyImport(parsed: FidelityImport): HoldingsImportResult {
        require(parsed.positions.isNotEmpty()) {
            "That file contained no positions."
        }

        val db = databases.require()
        val now = Instant.now().toEpochMilli()

        // Falls back to now when the footer is missing, but the footer is the
        // honest figure and the parser reads it, so this is a last resort.
        val asOf = parsed.downloadedAt
            ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: now

        val accountName = parsed.positions.first().accountName
        val entities = parsed.positions.map { it.toEntity(asOf, now) }

        val removed = db.holdingDao().getAll().size

        // Every holding is replaced, not just those matching the first
        // account name in the file.
        //
        // Scoping the delete to `positions.first().accountName` was wrong twice
        // over: a Fidelity export can contain several accounts, so only the
        // first was replaced, and if the account label differs at all between
        // exports nothing matches and the new rows are simply added. Either way
        // positions accumulate on every import and the totals double. That is
        // what happened.
        //
        // One transaction, so a failure cannot destroy the old snapshot without
        // writing the new one.
        db.withTransaction {
            db.holdingDao().deleteAll()
            db.holdingDao().insertAll(entities)
        }

        return HoldingsImportResult(
            accountName = accountName,
            positions = entities.count { !it.isCash },
            cashPositions = entities.count { it.isCash },
            total = Money.ofMinor(entities.sumOf { it.currentValueMinor }),
            asOf = Instant.ofEpochMilli(asOf),
            skipped = parsed.skipped.map { "${it.label}: ${it.reason}" },
            removed = removed
        )
    }

    suspend fun clear() {
        databases.require().holdingDao().deleteAll()
    }
}

/**
 * Dollars to integer cents, via BigDecimal on the string form.
 *
 * The parser hands back Doubles because that is what a CSV field decodes to.
 * Converting through the string avoids 158.00 arriving as 157.99999999999997
 * and then truncating to a cent short.
 */
private fun Double?.toMinor(): Long? = this?.let {
    BigDecimal(it.toString())
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()
}

private fun Double?.toQuantityMicros(): Long? = this?.let {
    BigDecimal(it.toString())
        .setScale(6, RoundingMode.HALF_UP)
        .movePointRight(6)
        .longValueExact()
}

private fun FidelityPosition.toEntity(asOf: Long, importedAt: Long) = HoldingEntity(
    accountName = accountName,
    symbol = symbol,
    description = description,
    quantityMicros = quantity.toQuantityMicros(),
    lastPriceMinor = lastPrice.toMinor(),
    currentValueMinor = currentValue.toMinor() ?: 0L,
    costBasisMinor = costBasisTotal.toMinor(),
    gainLossMinor = totalGainLossDollar.toMinor(),
    isCash = isCash,
    type = type,
    asOf = asOf,
    importedAt = importedAt
)

private fun HoldingEntity.toDomain() = Holding(
    id = id,
    accountName = accountName,
    symbol = symbol,
    description = description,
    quantity = quantityMicros?.let {
        BigDecimal(it).divide(BigDecimal(HoldingEntity.QUANTITY_SCALE))
    },
    lastPrice = lastPriceMinor?.let { Money.ofMinor(it) },
    currentValue = Money.ofMinor(currentValueMinor),
    costBasis = costBasisMinor?.let { Money.ofMinor(it) },
    gainLoss = gainLossMinor?.let { Money.ofMinor(it) },
    isCash = isCash,
    type = type,
    asOf = Instant.ofEpochMilli(asOf)
)
