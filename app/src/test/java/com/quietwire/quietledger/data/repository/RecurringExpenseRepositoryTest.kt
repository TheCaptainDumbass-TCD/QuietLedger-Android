package com.quietwire.quietledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.model.DebtCategory
import com.quietwire.quietledger.data.model.ExpenseGroup
import com.quietwire.quietledger.data.model.RecurringExpenseCategory
import com.quietwire.quietledger.domain.model.Debt
import com.quietwire.quietledger.domain.model.RecurringExpense
import com.quietwire.quietledger.domain.payoff.PayoffCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecurringExpenseRepositoryTest {

    private lateinit var database: QuietLedgerDatabase
    private lateinit var expenses: RecurringExpenseRepository
    private lateinit var debts: DebtRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, QuietLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val holder = object : DatabaseHolder {
            override fun require() = database
        }
        expenses = RecurringExpenseRepository(holder)
        debts = DebtRepository(holder)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun add(
        name: String,
        category: RecurringExpenseCategory,
        amount: String,
        frequency: Frequency = Frequency.MONTHLY
    ) = expenses.add(
        RecurringExpense(
            name = name,
            category = category,
            amount = Money.parseOrNull(amount)!!,
            frequency = frequency
        )
    )

    @Test
    fun `an expense round trips with its category`() = runTest {
        add("Electric", RecurringExpenseCategory.ELECTRIC, "142.50")

        val stored = expenses.observeActive().first().single()
        assertEquals("Electric", stored.name)
        assertEquals(RecurringExpenseCategory.ELECTRIC, stored.category)
        assertEquals(ExpenseGroup.UTILITIES, stored.category.group)
        assertEquals(Money.parseOrNull("142.50"), stored.amount)
    }

    @Test
    fun `the housing items a household actually has are all available`() = runTest {
        // The specific list asked for, confirming each is a real category rather
        // than falling through to OTHER.
        val wanted = listOf(
            RecurringExpenseCategory.RENT,
            RecurringExpenseCategory.LEASE,
            RecurringExpenseCategory.HOMEOWNERS_INSURANCE,
            RecurringExpenseCategory.RENTERS_INSURANCE,
            RecurringExpenseCategory.ELECTRIC,
            RecurringExpenseCategory.NATURAL_GAS,
            RecurringExpenseCategory.PROPANE,
            RecurringExpenseCategory.INTERNET,
            RecurringExpenseCategory.WATER
        )
        wanted.forEachIndexed { i, category ->
            add("item $i", category, "50.00")
        }

        val stored = expenses.observeActive().first()
        assertEquals(wanted.size, stored.size)
        assertEquals(wanted.toSet(), stored.map { it.category }.toSet())
        assertTrue(
            "nothing should land in OTHER",
            stored.none { it.category == RecurringExpenseCategory.OTHER }
        )
    }

    @Test
    fun `monthly total converts frequencies before adding them up`() = runTest {
        add("Rent", RecurringExpenseCategory.RENT, "1200.00")
        add("Trash", RecurringExpenseCategory.TRASH, "25.00", Frequency.QUARTERLY)
        add("Lawn", RecurringExpenseCategory.LAWN_CARE, "40.00", Frequency.WEEKLY)

        // 1200 monthly, 25 a quarter is 8.33 a month, 40 weekly is 173.33 a
        // month. Weekly is not four times monthly, which is one of the desktop
        // bugs this app does not reproduce.
        val total = expenses.observeMonthlyTotal().first()
        assertEquals(Money.parseOrNull("1381.66"), total)
    }

    @Test
    fun `totals roll up by group, sorted by size`() = runTest {
        add("Rent", RecurringExpenseCategory.RENT, "1200.00")
        add("Electric", RecurringExpenseCategory.ELECTRIC, "140.00")
        add("Water", RecurringExpenseCategory.WATER, "60.00")
        add("Renters", RecurringExpenseCategory.RENTERS_INSURANCE, "18.00")

        val byGroup = expenses.observeMonthlyByGroup().first()
        assertEquals(Money.parseOrNull("1200.00"), byGroup[ExpenseGroup.HOUSING])
        assertEquals(Money.parseOrNull("200.00"), byGroup[ExpenseGroup.UTILITIES])
        assertEquals(Money.parseOrNull("18.00"), byGroup[ExpenseGroup.INSURANCE])
        // Largest first, because that is the order worth reading.
        assertEquals(ExpenseGroup.HOUSING, byGroup.keys.first())
    }

    @Test
    fun `ending an expense keeps it out of the active list but does not delete it`() = runTest {
        val id = add("Netflix", RecurringExpenseCategory.STREAMING, "15.49")
        assertEquals(1, expenses.observeActive().first().size)

        expenses.end(id)

        assertTrue(expenses.observeActive().first().isEmpty())
        // Still there, so last year's spending stays readable.
        val all = expenses.observeAll().first()
        assertEquals(1, all.size)
        assertFalse(all.single().isActive)
        assertNotNull(all.single().endedAt)
    }

    @Test
    fun `an amount change is journalled, a name change is not`() = runTest {
        val id = add("Electric", RecurringExpenseCategory.ELECTRIC, "140.00")
        val stored = expenses.observeActive().first().single()

        expenses.update(stored.copy(name = "Electric company"))
        assertEquals("only the opening entry", 1, expenses.observeHistory(id).first().size)

        expenses.update(stored.copy(name = "Electric company", amount = Money.parseOrNull("155.00")!!))
        val history = expenses.observeHistory(id).first()
        assertEquals(2, history.size)
        assertEquals(1_500L, history.first().deltaMinor)
    }

    /**
     * The property that matters most. A recurring expense has no end, so if one
     * reached the snowball the debt-free date would be nonsense: it would either
     * never arrive, or silently absorb payment capacity that is actually
     * committed elsewhere.
     *
     * The desktop app keeps both in one table and relies on every query
     * remembering to filter by category. Here they are different tables, so this
     * test is really asserting that the separation holds end to end.
     */
    @Test
    fun `recurring expenses never enter the payoff calculation`() = runTest {
        debts.add(
            Debt(
                name = "Visa",
                category = DebtCategory.CREDIT_CARD,
                currentBalance = Money.parseOrNull("1000.00")!!,
                scheduledPayment = Money.parseOrNull("100.00")!!
            )
        )
        add("Rent", RecurringExpenseCategory.RENT, "1200.00")
        add("Electric", RecurringExpenseCategory.ELECTRIC, "140.00")

        val plan = PayoffCalculator().project(debts.getOpen())

        // Only the Visa is in the plan, and the figures reflect it alone.
        assertEquals(1, plan.projections.size)
        assertEquals("Visa", plan.projections.single().debt.name)
        assertEquals(Money.parseOrNull("100.00"), plan.startingMonthlyOutlay)
        assertEquals(10, plan.monthsToDebtFree)
        assertTrue("the plan must finish", plan.unpayable.isEmpty())
    }
}
