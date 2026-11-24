package com.quietwire.quietledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.DebtCategory
import com.quietwire.quietledger.domain.model.Debt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebtRepositoryTest {

    private lateinit var database: QuietLedgerDatabase
    private lateinit var repository: DebtRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Plain in-memory database. SQLCipher needs native libraries that a
        // JVM test cannot load, and encryption is not what this is checking.
        database = Room.inMemoryDatabaseBuilder(context, QuietLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = DebtRepository(object : DatabaseHolder {
            override fun require() = database
        })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun card(name: String = "Visa", balance: String = "1000.00") = Debt(
        name = name,
        category = DebtCategory.CREDIT_CARD,
        currentBalance = Money.parseOrNull(balance)!!,
        scheduledPayment = Money.parseOrNull("250.00")!!,
        paymentFrequency = Frequency.MONTHLY
    )

    @Test
    fun `adding a debt records its opening balance in the journal`() = runTest {
        val id = repository.add(card())

        val history = repository.observeHistory(id).first()
        assertEquals(1, history.size)
        with(history.single()) {
            assertEquals(ChangeSource.INITIAL, source)
            assertNull(previous)
            assertEquals(Money.parseOrNull("1000.00"), current)
            assertEquals(Money.parseOrNull("1000.00"), delta)
            assertEquals("Visa", subjectName)
        }
    }

    @Test
    fun `original balance defaults to the opening balance`() = runTest {
        val id = repository.add(card(balance = "1500.00"))
        val stored = repository.getById(id)!!
        assertEquals(Money.parseOrNull("1500.00"), stored.originalBalance)
    }

    @Test
    fun `a payment is journalled as a payment, not an edit`() = runTest {
        val id = repository.add(card())
        repository.recordPayment(id, Money.parseOrNull("250.00")!!, note = "October")

        val stored = repository.getById(id)!!
        assertEquals(Money.parseOrNull("750.00"), stored.currentBalance)

        val latest = repository.observeHistory(id).first().first()
        assertEquals(ChangeSource.PAYMENT, latest.source)
        assertEquals(Money.parseOrNull("-250.00"), latest.delta)
        assertEquals(Money.parseOrNull("1000.00"), latest.previous)
        assertEquals(Money.parseOrNull("750.00"), latest.current)
        assertEquals("October", latest.note)
        assertTrue(latest.isDecrease)
    }

    @Test
    fun `history accumulates in order, newest first`() = runTest {
        val id = repository.add(card())
        repository.recordPayment(id, Money.parseOrNull("100.00")!!)
        repository.recordPayment(id, Money.parseOrNull("200.00")!!)

        val history = repository.observeHistory(id).first()
        assertEquals(3, history.size)
        assertEquals(
            listOf("-200.00", "-100.00", "1000.00").map { Money.parseOrNull(it) },
            history.map { it.delta }
        )
    }

    @Test
    fun `only the fields that actually changed are journalled`() = runTest {
        val id = repository.add(card())
        val stored = repository.getById(id)!!

        // Rename only. No amount moved, so no new journal row.
        repository.update(stored.copy(name = "Visa Signature"))
        assertEquals(1, repository.observeHistory(id).first().size)

        // Now change the payment as well.
        repository.update(stored.copy(scheduledPayment = Money.parseOrNull("300.00")!!))
        val history = repository.observeHistory(id).first()
        assertEquals(2, history.size)
        assertEquals(DebtRepository.FIELD_SCHEDULED_PAYMENT, history.first().fieldName)
        // The description should read as a sentence, not expose column names.
        assertTrue(history.first().description.startsWith("Payment changed to"))
    }

    @Test
    fun `paying a debt to zero leaves it on the list`() = runTest {
        // Changed deliberately. Clearing a card used to close it, which removed
        // it from every screen without being asked. A cleared card is often
        // worth keeping visible, and the app should not decide to hide a
        // financial record. Removal is explicit, via Delete.
        val id = repository.add(card())
        repository.recordPayment(id, Money.parseOrNull("1000.00")!!)

        val stored = repository.getById(id)!!
        assertEquals(Money.ZERO, stored.currentBalance)
        assertNull("nothing may close it on its own", stored.closedAt)
        assertEquals(1, repository.observeOpen().first().size)
    }

    @Test
    fun `overpaying stops at zero rather than going negative`() = runTest {
        val id = repository.add(card())
        repository.recordPayment(id, Money.parseOrNull("5000.00")!!)
        assertEquals(Money.ZERO, repository.getById(id)!!.currentBalance)
    }

    @Test
    fun `a balance going to zero and back never hides the debt`() = runTest {
        val id = repository.add(card())
        repository.recordPayment(id, Money.parseOrNull("1000.00")!!)
        assertEquals("still visible at zero", 1, repository.observeOpen().first().size)

        val cleared = repository.getById(id)!!
        repository.update(cleared.copy(currentBalance = Money.parseOrNull("50.00")!!))

        assertNull(repository.getById(id)!!.closedAt)
        assertEquals(1, repository.observeOpen().first().size)
        assertEquals(Money.parseOrNull("50.00"), repository.getById(id)!!.currentBalance)
    }

    @Test
    fun `outstanding total covers open debts only`() = runTest {
        val a = repository.add(card("A", "1000.00"))
        repository.add(card("B", "500.00"))
        repository.recordPayment(a, Money.parseOrNull("1000.00")!!)

        assertEquals(Money.parseOrNull("500.00"), repository.observeTotalOutstanding().first())
    }

    @Test
    fun `a rejected payment leaves nothing behind`() = runTest {
        val id = repository.add(card())
        try {
            repository.recordPayment(id, Money.parseOrNull("-5.00")!!)
            throw AssertionError("expected a negative payment to be rejected")
        } catch (expected: IllegalArgumentException) {
            // Correct.
        }
        assertEquals(1, repository.observeHistory(id).first().size)
        assertEquals(Money.parseOrNull("1000.00"), repository.getById(id)!!.currentBalance)
    }

    @Test
    fun `new debts get sequential sort order`() = runTest {
        val first = repository.getById(repository.add(card("A")))!!
        val second = repository.getById(repository.add(card("B")))!!
        assertTrue(second.sortOrder > first.sortOrder)
    }

    /**
     * The reported bug: adding an interest rate made a debt disappear.
     *
     * The rate was irrelevant. Any save of a zero-balance debt closed it,
     * because the rule looked at whether the balance *was* zero rather than
     * whether it had just *fallen* to zero. A credit card sitting at zero is
     * still an open account, and putting its APR in is a normal thing to do.
     */
    @Test
    fun `editing a zero balance debt does not close it`() = runTest {
        val id = repository.add(
            Debt(
                name = "Fidelity Visa",
                category = DebtCategory.CREDIT_CARD,
                currentBalance = Money.ZERO,
                scheduledPayment = Money.ZERO
            )
        )
        assertEquals("starts visible", 1, repository.observeOpen().first().size)

        val card = repository.observeOpen().first().single()
        repository.update(card.copy(aprBasisPoints = 2499))

        val remaining = repository.observeOpen().first()
        assertEquals("a zero-balance card must stay visible", 1, remaining.size)
        assertEquals(2499, remaining.single().aprBasisPoints)
        assertNull("and must not be closed", remaining.single().closedAt)
    }

    /**
     * Paying a debt off leaves it on the list.
     *
     * Deliberate, and a reversal. The app used to close a debt the moment its
     * balance hit zero, which meant a record disappeared without being asked
     * to. A cleared card is often worth keeping visible, and hiding a financial
     * record is not a decision the app should take on its own. Removing an
     * entry is explicit, behind a Delete button.
     */
    @Test
    fun `a debt paid down to zero stays on the list`() = runTest {
        val id = repository.add(
            Debt(
                name = "Visa",
                category = DebtCategory.CREDIT_CARD,
                currentBalance = Money.parseOrNull("500.00")!!,
                scheduledPayment = Money.parseOrNull("50.00")!!
            )
        )

        val card = repository.observeOpen().first().single()
        repository.update(card.copy(currentBalance = Money.ZERO))

        val remaining = repository.observeOpen().first()
        assertEquals("a cleared debt is still visible", 1, remaining.size)
        assertEquals(Money.ZERO, remaining.single().currentBalance)
        assertNull("and nothing closed it", remaining.single().closedAt)
    }

    @Test
    fun `delete removes a debt and its journal`() = runTest {
        val id = repository.add(
            Debt(
                name = "Store card",
                category = DebtCategory.CREDIT_CARD,
                currentBalance = Money.parseOrNull("100.00")!!,
                scheduledPayment = Money.parseOrNull("25.00")!!
            )
        )
        assertEquals(1, repository.observeOpen().first().size)

        // The explicit route, now that nothing happens implicitly.
        repository.delete(id)

        assertTrue(repository.observeOpen().first().isEmpty())
        assertTrue(repository.observeAll().first().isEmpty())
        assertTrue("its history goes with it", repository.observeHistory(id).first().isEmpty())
        assertTrue(
            "and nothing is left in the activity feed",
            repository.observeRecentActivity(limit = 20).first().none { it.subjectId == id }
        )
    }

    @Test
    fun `editing never changes whether a debt is closed`() = runTest {
        val id = repository.add(
            Debt(
                name = "Store card",
                category = DebtCategory.CREDIT_CARD,
                currentBalance = Money.parseOrNull("100.00")!!,
                scheduledPayment = Money.parseOrNull("25.00")!!
            )
        )
        val card = repository.observeOpen().first().single()

        // Balance to zero, rate added, renamed: none of it may hide the debt.
        repository.update(card.copy(currentBalance = Money.ZERO))
        repository.update(
            repository.observeOpen().first().single().copy(aprBasisPoints = 2499)
        )
        repository.update(
            repository.observeOpen().first().single().copy(name = "Store card (old)")
        )

        val remaining = repository.observeOpen().first()
        assertEquals(1, remaining.size)
        assertNull(remaining.single().closedAt)
        assertEquals(2499, remaining.single().aprBasisPoints)
    }
}
