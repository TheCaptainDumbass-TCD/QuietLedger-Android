package com.quietwire.quietledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.SavingsPurpose
import com.quietwire.quietledger.domain.model.SavingsAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavingsRepositoryTest {

    private lateinit var database: QuietLedgerDatabase
    private lateinit var savings: SavingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuietLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        savings = SavingsRepository(object : DatabaseHolder {
            override fun require() = database
        })
    }

    @After
    fun tearDown() = database.close()

    private suspend fun add(
        name: String,
        balance: String,
        apyBasisPoints: Int? = null,
        goal: String? = null,
        purpose: SavingsPurpose = SavingsPurpose.GENERAL
    ) = savings.add(
        SavingsAccount(
            name = name,
            currentBalance = Money.parseOrNull(balance)!!,
            apyBasisPoints = apyBasisPoints,
            goal = goal?.let { Money.parseOrNull(it) },
            purpose = purpose
        )
    )

    @Test
    fun `an account round trips`() = runTest {
        add("Emergency Fund", "5250.00", apyBasisPoints = 425,
            purpose = SavingsPurpose.EMERGENCY)

        val stored = savings.observeOpen().first().single()
        assertEquals("Emergency Fund", stored.name)
        assertEquals(Money.parseOrNull("5250.00"), stored.currentBalance)
        assertEquals(425, stored.apyBasisPoints)
        assertEquals(SavingsPurpose.EMERGENCY, stored.purpose)
    }

    @Test
    fun `the total is what net worth adds`() = runTest {
        add("Emergency", "5000.00")
        add("Cash", "1254.63")
        add("Checking", "812.40")

        assertEquals(Money.parseOrNull("7067.03"), savings.observeTotal().first())
    }

    @Test
    fun `the total is zero with no accounts, not null`() = runTest {
        // SUM over no rows is null in SQLite, and a null total silently becomes
        // zero on some paths and throws on others.
        assertEquals(Money.ZERO, savings.observeTotal().first())
    }

    @Test
    fun `opening a balance is journalled`() = runTest {
        val id = add("Emergency", "5000.00")

        val history = savings.observeHistory(id).first()
        assertEquals(1, history.size)
        assertEquals(ChangeSource.INITIAL.name, history.single().source)
        assertEquals(500_000L, history.single().newMinor)
        assertNull(history.single().previousMinor)
    }

    @Test
    fun `a deposit raises the balance and records what it was for`() = runTest {
        val id = add("Emergency", "5000.00")

        savings.recordMovement(id, Money.parseOrNull("250.00")!!, note = "payday")

        assertEquals(Money.parseOrNull("5250.00"), savings.observeTotal().first())
        val latest = savings.observeHistory(id).first().first()
        assertEquals(25_000L, latest.deltaMinor)
        assertEquals("payday", latest.note)
        assertEquals(ChangeSource.PAYMENT.name, latest.source)
    }

    @Test
    fun `a withdrawal is a negative movement, not a separate concept`() = runTest {
        val id = add("Emergency", "5000.00")

        savings.recordMovement(id, -Money.parseOrNull("800.00")!!, note = "car repair")

        assertEquals(Money.parseOrNull("4200.00"), savings.observeTotal().first())
        val latest = savings.observeHistory(id).first().first()
        assertEquals(-80_000L, latest.deltaMinor)
        assertEquals("car repair", latest.note)
    }

    @Test
    fun `a zero movement changes nothing and is not journalled`() = runTest {
        val id = add("Emergency", "5000.00")
        savings.recordMovement(id, Money.ZERO)

        assertEquals(Money.parseOrNull("5000.00"), savings.observeTotal().first())
        assertEquals("only the opening entry", 1, savings.observeHistory(id).first().size)
    }

    @Test
    fun `renaming is not journalled, a balance change is`() = runTest {
        val id = add("Emergency", "5000.00")
        val stored = savings.observeOpen().first().single()

        savings.update(stored.copy(name = "Rainy day fund"))
        assertEquals(1, savings.observeHistory(id).first().size)

        savings.update(stored.copy(name = "Rainy day fund",
            currentBalance = Money.parseOrNull("5500.00")!!))
        assertEquals(2, savings.observeHistory(id).first().size)
    }

    @Test
    fun `closing an account removes it from the total but keeps its history`() = runTest {
        val id = add("Old ISA", "1200.00")
        add("Emergency", "5000.00")
        assertEquals(Money.parseOrNull("6200.00"), savings.observeTotal().first())

        savings.close(id)

        // Closed money is not money you have, so it leaves net worth.
        assertEquals(Money.parseOrNull("5000.00"), savings.observeTotal().first())
        assertEquals(1, savings.observeOpen().first().size)
        assertEquals(2, savings.observeAll().first().size)
        assertFalse(savings.observeAll().first().single { it.id == id }.isOpen)
        assertEquals("history survives", 1, savings.observeHistory(id).first().size)
    }

    @Test
    fun `projected interest uses basis points, not a float rate`() = runTest {
        add("Emergency", "10000.00", apyBasisPoints = 425)

        // 10,000 at 4.25% is 425.00 exactly.
        val account = savings.observeOpen().first().single()
        assertEquals(Money.parseOrNull("425.00"), account.projectedAnnualInterest)
    }

    @Test
    fun `no rate means no projected interest rather than zero`() = runTest {
        add("Cash", "500.00")
        assertNull(savings.observeOpen().first().single().projectedAnnualInterest)
    }

    @Test
    fun `goal progress is reported and capped`() = runTest {
        add("Holiday", "750.00", goal = "1000.00")
        val account = savings.observeOpen().first().single()

        assertEquals(0.75f, account.goalProgress!!, 0.001f)
        assertEquals(Money.parseOrNull("250.00"), account.remainingToGoal)
        assertFalse(account.goalReached)
    }

    @Test
    fun `exceeding a goal caps the bar but reports it as reached`() = runTest {
        add("Holiday", "1500.00", goal = "1000.00")
        val account = savings.observeOpen().first().single()

        // Capped so a progress bar cannot overflow, but the flag tells the truth.
        assertEquals(1f, account.goalProgress!!, 0.001f)
        assertTrue(account.goalReached)
        assertNull("nothing left to save", account.remainingToGoal)
    }
}
