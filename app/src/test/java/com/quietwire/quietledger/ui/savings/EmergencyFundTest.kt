package com.quietwire.quietledger.ui.savings

import com.quietwire.quietledger.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emergency fund rungs.
 *
 * Sized against recurring expenses plus debt payments, which is what actually
 * leaves the account each month. Expenses alone would measure surviving rather
 * than continuing to pay what is owed, and the two answers differ by a factor
 * of three on this data.
 */
class EmergencyFundTest {

    private fun state(saved: String, need: String) = SavingsUiState(
        total = Money.parseOrNull(saved)!!,
        monthlyNeed = Money.parseOrNull(need)!!,
        loading = false
    )

    @Test
    fun `targets are one, three and six months of everything`() {
        val rungs = state(saved = "3088.93", need = "10409.76").emergencyFund

        assertEquals(listOf(1, 3, 6), rungs.map { it.months })
        assertEquals(Money.parseOrNull("10409.76"), rungs[0].target)
        assertEquals(Money.parseOrNull("31229.28"), rungs[1].target)
        assertEquals(Money.parseOrNull("62458.56"), rungs[2].target)
    }

    @Test
    fun `the shortfall is what is still needed`() {
        val rungs = state(saved = "3088.93", need = "10409.76").emergencyFund

        assertEquals(Money.parseOrNull("7320.83"), rungs[0].shortfall)
        rungs.forEach { assertFalse(it.reached) }
    }

    @Test
    fun `a reached rung reports no shortfall`() {
        val rungs = state(saved = "40000.00", need = "10000.00").emergencyFund

        assertTrue(rungs[0].reached)
        assertTrue(rungs[1].reached)
        assertFalse(rungs[2].reached)
        assertEquals(Money.ZERO, rungs[1].shortfall)
        assertEquals(Money.parseOrNull("20000.00"), rungs[2].shortfall)
    }

    @Test
    fun `progress is capped so a bar cannot overrun`() {
        val rungs = state(saved = "40000.00", need = "10000.00").emergencyFund

        assertEquals(1f, rungs[0].progress, 0.001f)
        assertEquals(0.666f, rungs[2].progress, 0.01f)
    }

    @Test
    fun `months covered is the headline figure`() {
        assertEquals(0.297f, state("3088.93", "10409.76").monthsCovered, 0.001f)
    }

    @Test
    fun `no monthly figure means no targets rather than division by zero`() {
        // Before any expenses or debts exist there is nothing to size against,
        // and inventing a target of zero would report it as already met.
        val empty = state(saved = "500.00", need = "0.00")

        assertTrue(empty.emergencyFund.isEmpty())
        assertEquals(0f, empty.monthsCovered, 0.001f)
    }
}
