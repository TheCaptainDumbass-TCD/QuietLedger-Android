package com.quietwire.quietledger.data.repository

import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.db.entity.MetalLotEntity
import com.quietwire.quietledger.data.db.entity.MetalSpotPriceEntity
import com.quietwire.quietledger.domain.model.Metal
import com.quietwire.quietledger.domain.model.MetalLot
import com.quietwire.quietledger.domain.model.MetalSummary
import com.quietwire.quietledger.domain.model.SpotPrice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bullion holdings, as purchase lots from vendor invoices.
 *
 * Valued at cost. There is no spot feed and no network permission, and for a
 * holding that is not being traded what was paid is the honest figure. Showing
 * a market value would require a price this app cannot obtain and would change
 * net worth on information it does not have.
 */
@Singleton
class MetalsRepository @Inject constructor(
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

    fun observeAll(): Flow<List<MetalLot>> =
        fromDatabase { it.metalLotDao().observeAll() }
            .map { list -> list.mapNotNull { l -> l.toDomain() } }

    /** Total paid, including allocated shipping. The metals part of net worth. */
    fun observeTotalCost(): Flow<Money> =
        fromDatabase { it.metalLotDao().observeTotalCost() }.map { Money.ofMinor(it) }

    fun observeSpotPrices(): Flow<Map<Metal, SpotPrice>> =
        fromDatabase { it.metalSpotPriceDao().observeAll() }
            .map { rows ->
                rows.mapNotNull { row ->
                    Metal.fromStored(row.metal)?.let { metal ->
                        metal to SpotPrice(
                            metal = metal,
                            quotedPrice = Money.ofMinor(row.pricePerOzMinor),
                            enteredAt = Instant.ofEpochMilli(row.enteredAt)
                        )
                    }
                }.toMap()
            }

    fun observeSummaries(): Flow<List<MetalSummary>> =
        combine(observeAll(), observeSpotPrices()) { lots, prices ->
            lots.groupBy { it.metal }
                .map { (metal, group) ->
                    MetalSummary(
                        metal = metal,
                        ounces = group.fold(BigDecimal.ZERO) { a, l -> a + l.totalOunces },
                        cost = group.fold(Money.ZERO) { a, l -> a + l.totalCost },
                        lots = group.size,
                        spot = prices[metal]
                    )
                }
                .sortedByDescending { it.cost.minorUnits }
        }

    /**
     * Total market value, or null until every held metal has a price.
     *
     * All or nothing on purpose. Mixing valued and unvalued metals into one
     * figure would silently report part of the holding at market and the rest
     * at cost, which is a number that means nothing and cannot be checked.
     */
    fun observeMarketValue(): Flow<Money?> = observeSummaries().map { summaries ->
        if (summaries.isEmpty() || summaries.any { it.marketValue == null }) {
            null
        } else {
            summaries.fold(Money.ZERO) { a, s -> a + s.marketValue!! }
        }
    }

    /**
     * Stores a price exactly as quoted for that metal: per troy ounce for gold
     * and silver, per pound for copper. Converting here would round a
     * sub-dollar figure to the cent and lose over 1% of a copper valuation.
     */
    suspend fun setSpotPrice(metal: Metal, quotedPrice: Money) {
        require(quotedPrice.isPositive) { "A spot price has to be more than nothing." }
        databases.require().metalSpotPriceDao().upsert(
            MetalSpotPriceEntity(
                metal = metal.name,
                pricePerOzMinor = quotedPrice.minorUnits,
                enteredAt = Instant.now().toEpochMilli()
            )
        )
    }

    suspend fun clearSpotPrice(metal: Metal) {
        databases.require().metalSpotPriceDao().delete(metal.name)
    }

    suspend fun replaceAll(lots: List<MetalLot>) {
        val db = databases.require()
        val now = Instant.now().toEpochMilli()
        db.metalLotDao().deleteAll()
        db.metalLotDao().insertAll(lots.map { it.toEntity(now) })
    }

    suspend fun clear() {
        databases.require().metalLotDao().deleteAll()
    }
}

private fun MetalLot.toEntity(createdAt: Long) = MetalLotEntity(
    vendor = vendor,
    orderNumber = orderNumber,
    purchasedAt = purchasedAt.toEpochMilli(),
    description = description,
    metal = metal.name,
    quantityMilli = (quantity * BigDecimal(MetalLotEntity.QUANTITY_SCALE))
        .setScale(0, RoundingMode.HALF_UP).toLong(),
    unitWeightMicroOz = (unitWeightOz * BigDecimal(MetalLotEntity.WEIGHT_SCALE))
        .setScale(0, RoundingMode.HALF_UP).toLong(),
    weightLabel = weightLabel,
    unitPriceMinor = unitPrice.minorUnits,
    extendedPriceMinor = extendedPrice.minorUnits,
    shippingMinor = shipping.minorUnits,
    spotPriceMinor = spotPrice?.minorUnits,
    sourceFile = sourceFile,
    createdAt = createdAt
)

/**
 * Null for an unrecognised metal rather than a fallback.
 *
 * Only gold, silver and copper are tracked. A row naming something else is a
 * sign the import was wrong, and quietly filing it under one of the three would
 * corrupt both the ounce count and the cost basis.
 */
private fun MetalLotEntity.toDomain(): MetalLot? {
    val known = Metal.fromStored(metal) ?: return null
    return MetalLot(
        id = id,
        vendor = vendor,
        orderNumber = orderNumber,
        purchasedAt = Instant.ofEpochMilli(purchasedAt),
        description = description,
        metal = known,
        quantity = BigDecimal(quantityMilli)
            .divide(BigDecimal(MetalLotEntity.QUANTITY_SCALE)),
        unitWeightOz = BigDecimal(unitWeightMicroOz)
            .divide(BigDecimal(MetalLotEntity.WEIGHT_SCALE)),
        weightLabel = weightLabel,
        unitPrice = Money.ofMinor(unitPriceMinor),
        extendedPrice = Money.ofMinor(extendedPriceMinor),
        shipping = Money.ofMinor(shippingMinor),
        spotPrice = spotPriceMinor?.let { Money.ofMinor(it) },
        sourceFile = sourceFile
    )
}
