package com.quietwire.quietledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.db.entity.MetalLotEntity
import com.quietwire.quietledger.domain.model.Metal
import com.quietwire.quietledger.domain.model.MetalLot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetalsRepositoryTest {

    private lateinit var database: QuietLedgerDatabase
    private lateinit var metals: MetalsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuietLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        metals = MetalsRepository(object : DatabaseHolder {
            override fun require() = database
        })
    }

    @After
    fun tearDown() = database.close()

    private fun lot(
        metal: Metal,
        quantity: String,
        unitWeightOz: String,
        extended: String,
        shipping: String = "0.00",
        unitPrice: String = "0.00",
        spot: String? = null
    ) = MetalLot(
        vendor = "SD Bullion",
        orderNumber = "1",
        purchasedAt = Instant.parse("2024-12-18T00:00:00Z"),
        description = "${unitWeightOz} oz ${metal.label} Round",
        metal = metal,
        quantity = BigDecimal(quantity),
        unitWeightOz = BigDecimal(unitWeightOz),
        unitPrice = Money.parseOrNull(unitPrice)!!,
        extendedPrice = Money.parseOrNull(extended)!!,
        shipping = Money.parseOrNull(shipping)!!,
        spotPrice = spot?.let { Money.parseOrNull(it) }
    )

    @Test
    fun `a lot round trips through scaled integers`() = runTest {
        metals.replaceAll(listOf(lot(Metal.GOLD, "1", "0.1", "250.00")))

        val stored = metals.observeAll().first().single()
        assertEquals(Metal.GOLD, stored.metal)
        // A tenth-ounce coin survives as a tenth, not as 0.0999999.
        assertEquals(BigDecimal("0.1"), stored.unitWeightOz.stripTrailingZeros())
        // compareTo, not equals: BigDecimal.equals compares scale too, so
        // "0.1" and "0.1000" are unequal. This passed before only because
        // totalOunces forced a scale of 4, which is the rounding that caused
        // the five dollar divergence.
        assertEquals(0, BigDecimal("0.1").compareTo(stored.totalOunces))
    }

    @Test
    fun `total cost includes allocated shipping, because that was paid too`() = runTest {
        metals.replaceAll(
            listOf(
                lot(Metal.SILVER, "5", "1", "150.00", shipping = "7.50"),
                lot(Metal.SILVER, "2", "1", "60.00", shipping = "2.45")
            )
        )

        assertEquals(Money.parseOrNull("219.95"), metals.observeTotalCost().first())
    }

    @Test
    fun `the total is zero with nothing recorded, not null`() = runTest {
        assertEquals(Money.ZERO, metals.observeTotalCost().first())
    }

    @Test
    fun `summaries roll up ounces and cost per metal`() = runTest {
        metals.replaceAll(
            listOf(
                lot(Metal.SILVER, "10", "1", "300.00", shipping = "10.00"),
                lot(Metal.SILVER, "5", "1", "150.00"),
                lot(Metal.GOLD, "1", "0.25", "650.00")
            )
        )

        val summaries = metals.observeSummaries().first()
        // Sorted by cost, so gold leads despite being a fraction of an ounce.
        assertEquals(Metal.GOLD, summaries.first().metal)

        val silver = summaries.single { it.metal == Metal.SILVER }
        assertEquals(0, BigDecimal("15").compareTo(silver.ounces))
        assertEquals(Money.parseOrNull("460.00"), silver.cost)
        assertEquals(2, silver.lots)
    }

    @Test
    fun `average cost per ounce accounts for shipping`() = runTest {
        metals.replaceAll(
            listOf(lot(Metal.SILVER, "10", "1", "300.00", shipping = "10.00"))
        )

        // 310.00 over 10 oz is 31.00, not the 30.00 the line price alone implies.
        val silver = metals.observeSummaries().first().single()
        assertEquals(Money.parseOrNull("31.00"), silver.averageCostPerOunce)
    }

    @Test
    fun `premium over spot comes out of the invoice figures`() = runTest {
        // A 1 oz round bought at 30.00 with silver at 25.00 is 20% over melt.
        metals.replaceAll(
            listOf(lot(Metal.SILVER, "1", "1", "30.00", unitPrice = "30.00", spot = "25.00"))
        )

        assertEquals(0.20, metals.observeAll().first().single().premiumOverSpot!!, 0.001)
    }

    @Test
    fun `a fractional coin premium uses melt value, not the spot price directly`() = runTest {
        // A 1/10 oz gold coin at 250 with gold at 2000 has melt of 200, so 25%.
        // Comparing 250 against 2000 would report it as 87% below spot.
        metals.replaceAll(
            listOf(lot(Metal.GOLD, "1", "0.1", "250.00", unitPrice = "250.00", spot = "2000.00"))
        )

        assertEquals(0.25, metals.observeAll().first().single().premiumOverSpot!!, 0.001)
    }

    @Test
    fun `no spot price means no premium rather than zero`() = runTest {
        // Zero would claim it was bought at melt, which is a different statement
        // from not knowing.
        metals.replaceAll(listOf(lot(Metal.SILVER, "1", "1", "30.00")))
        assertNull(metals.observeAll().first().single().premiumOverSpot)
    }

    @Test
    fun `an unrecognised metal is dropped rather than filed under a known one`() = runTest {
        database.metalLotDao().insert(
            MetalLotEntity(
                vendor = "APMEX",
                orderNumber = "9",
                purchasedAt = 0,
                description = "1 oz Platinum Bar",
                metal = "PLATINUM",
                quantityMilli = 1_000,
                unitWeightMicroOz = 1_000_000,
                unitPriceMinor = 100_000,
                extendedPriceMinor = 100_000,
                createdAt = 0
            )
        )

        // Tracking is gold, silver and copper. Quietly treating platinum as one
        // of them would corrupt both the ounce count and the cost basis, so the
        // mapper refuses it.
        assertTrue(metals.observeAll().first().isEmpty())
        assertTrue(metals.observeSummaries().first().isEmpty())
    }

    @Test
    fun `replacing clears what was there, since a reparse is the whole set`() = runTest {
        metals.replaceAll(listOf(lot(Metal.SILVER, "10", "1", "300.00")))
        metals.replaceAll(listOf(lot(Metal.GOLD, "1", "1", "2500.00")))

        val remaining = metals.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals(Metal.GOLD, remaining.single().metal)
        assertEquals(Money.parseOrNull("2500.00"), metals.observeTotalCost().first())
    }

    @Test
    fun `market value is null until every held metal has a price`() = runTest {
        metals.replaceAll(
            listOf(
                lot(Metal.SILVER, "10", "1", "300.00"),
                lot(Metal.GOLD, "1", "1", "2500.00")
            )
        )
        assertNull("nothing priced yet", metals.observeMarketValue().first())

        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("31.00")!!)

        // Deliberately still null. Silver at market plus gold at cost would be
        // a number that corresponds to nothing and cannot be checked.
        assertNull("only one of two priced", metals.observeMarketValue().first())

        metals.setSpotPrice(Metal.GOLD, Money.parseOrNull("2650.00")!!)

        assertEquals(Money.parseOrNull("2960.00"), metals.observeMarketValue().first())
    }

    @Test
    fun `a summary values its ounces at the entered price`() = runTest {
        metals.replaceAll(
            listOf(lot(Metal.SILVER, "10", "1", "300.00", shipping = "10.00"))
        )
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("31.00")!!)

        val silver = metals.observeSummaries().first().single()
        assertEquals(Money.parseOrNull("310.00"), silver.marketValue)
        // Cost was 310.00 including shipping, so this is exactly break even.
        assertEquals(Money.ZERO, silver.gain)
    }

    @Test
    fun `a loss is reported as a loss`() = runTest {
        metals.replaceAll(
            listOf(lot(Metal.SILVER, "10", "1", "400.00"))
        )
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("31.00")!!)

        val silver = metals.observeSummaries().first().single()
        assertEquals(Money.parseOrNull("-90.00"), silver.gain)
        assertEquals(-0.225, silver.gainFraction!!, 0.0001)
    }

    @Test
    fun `fractional ounces are valued without losing cents`() = runTest {
        // A tenth-ounce gold coin at 2650 is 265.00, not 265.0000001 or 264.99.
        metals.replaceAll(listOf(lot(Metal.GOLD, "1", "0.1", "250.00")))
        metals.setSpotPrice(Metal.GOLD, Money.parseOrNull("2650.00")!!)

        assertEquals(
            Money.parseOrNull("265.00"),
            metals.observeSummaries().first().single().marketValue
        )
    }

    @Test
    fun `clearing a price makes the valuation honestly incomplete again`() = runTest {
        metals.replaceAll(listOf(lot(Metal.SILVER, "10", "1", "300.00")))
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("31.00")!!)
        assertEquals(Money.parseOrNull("310.00"), metals.observeMarketValue().first())

        metals.clearSpotPrice(Metal.SILVER)

        // Back to null rather than keeping a stale price, so the screen falls
        // back to cost instead of quoting a figure nobody stands behind.
        assertNull(metals.observeMarketValue().first())
    }

    @Test
    fun `re-entering a price replaces it rather than adding a second`() = runTest {
        metals.replaceAll(listOf(lot(Metal.SILVER, "10", "1", "300.00")))
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("31.00")!!)
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("33.50")!!)

        assertEquals(1, metals.observeSpotPrices().first().size)
        assertEquals(Money.parseOrNull("335.00"), metals.observeMarketValue().first())
    }

    @Test
    fun `a price of zero or less is refused`() = runTest {
        metals.replaceAll(listOf(lot(Metal.SILVER, "1", "1", "30.00")))

        // Zero would value the holding at nothing, which is not a price.
        assertTrue(
            runCatching { metals.setSpotPrice(Metal.SILVER, Money.ZERO) }.isFailure
        )
        assertNull(metals.observeMarketValue().first())
    }

    @Test
    fun `replacing lots keeps the entered prices`() = runTest {
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("31.00")!!)
        metals.replaceAll(listOf(lot(Metal.SILVER, "10", "1", "300.00")))

        // Re-parsing invoices should not throw away prices, which are a
        // separate thing the user supplied.
        assertEquals(Money.parseOrNull("310.00"), metals.observeMarketValue().first())
    }



    @Test
    fun `gold and silver are quoted per troy ounce and used directly`() = runTest {
        metals.replaceAll(
            listOf(
                lot(Metal.GOLD, "1", "0.1", "250.00"),
                lot(Metal.SILVER, "10", "1", "300.00")
            )
        )
        metals.setSpotPrice(Metal.GOLD, Money.parseOrNull("4174.10")!!)
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("61.90")!!)

        val summaries = metals.observeSummaries().first()
        // A tenth of an ounce of gold at 4174.10 is 417.41.
        assertEquals(
            Money.parseOrNull("417.41"),
            summaries.single { it.metal == Metal.GOLD }.marketValue
        )
        assertEquals(
            Money.parseOrNull("619.00"),
            summaries.single { it.metal == Metal.SILVER }.marketValue
        )
    }

    @Test
    fun `a stored price is shown back exactly as it was quoted`() = runTest {
        // Nothing is normalised on the way in, so nothing drifts on the way out.
        metals.replaceAll(listOf(lot(Metal.SILVER, "19", "1", "42.31")))
        metals.setSpotPrice(Metal.SILVER, Money.parseOrNull("6.65")!!)

        assertEquals(
            Money.parseOrNull("6.65"),
            metals.observeSpotPrices().first()[Metal.SILVER]!!.quotedPrice
        )
    }



    @Test
    fun `an ordinary bullion premium is unaffected`() = runTest {
        metals.replaceAll(
            listOf(lot(Metal.SILVER, "1", "1", "30.00", unitPrice = "30.00", spot = "25.00"))
        )

        val stored = metals.observeAll().first().single()
        assertEquals(0.20, stored.premiumOverSpot!!, 0.001)
        assertFalse(stored.spotLooksUnreliable)
    }


    @Test
    fun `a supplied spot of zero yields no premium rather than dividing by it`() = runTest {
        metals.replaceAll(listOf(lot(Metal.SILVER, "1", "1", "30.00", unitPrice = "30.00")))
        assertNull(metals.observeAll().first().single().premiumOver(Money.ZERO))
    }

    @Test
    fun `weights are summed exactly rather than rounded lot by lot`() = runTest {
        // Take 28 lots of 0.032151 oz of gold. Rounding each
        // to four decimals gives 0.0322, so every lot gains 0.000049 oz and the
        // total gains 0.0012 oz. At spot that was five dollars of gold that did
        // not exist, and it only came to light because the server computed the
        // same data and disagreed.
        val lots = (1..28).map {
            lot(Metal.GOLD, "1", "0.032151", "100.00")
        }
        metals.replaceAll(lots)

        val summary = metals.observeSummaries().first().single()

        // 28 x 0.032151 exactly.
        assertEquals(BigDecimal("0.900228"), summary.ounces.stripTrailingZeros())
        assertNotEquals(
            "must not be the accumulated rounding",
            BigDecimal("0.9016"),
            summary.ounces.stripTrailingZeros()
        )
    }

    @Test
    fun `a valuation uses the exact weight`() = runTest {
        metals.replaceAll(
            (1..28).map { lot(Metal.GOLD, "1", "0.032151", "100.00") }
        )
        metals.setSpotPrice(Metal.GOLD, Money.parseOrNull("4123.34")!!)

        // 0.900228 x 4123.34 = 3711.95. Rounded weights would give 3717.60,
        // which is 5.66 of gold that does not exist.
        assertEquals(Money.parseOrNull("3711.95"), metals.observeMarketValue().first())
        assertNotEquals(Money.parseOrNull("3717.60"), metals.observeMarketValue().first())
    }

    @Test
    fun `the displayed weight is still rounded for the screen`() = runTest {
        // Exact arithmetic does not mean ugly output. 0.900228 is shown to four
        // decimals; the sum behind it keeps every digit.
        metals.replaceAll(
            (1..28).map { lot(Metal.GOLD, "1", "0.032151", "100.00") }
        )

        assertEquals("0.9002", metals.observeSummaries().first().single().ouncesLabel)
    }
}
