package com.quietwire.quietledger.ui.dashboard

import com.quietwire.quietledger.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Net worth is assets minus liabilities, and the sign is the whole point.
 *
 * Worth testing despite being one subtraction, because a sign error here is
 * silently plausible: a positive number where a negative belongs still looks
 * like a sensible figure, and the headline card is the first thing read.
 */
class NetWorthTest {

    private fun state(debt: String, assets: String = "0.00", hasAssets: Boolean = false) =
        DashboardUiState(
            totalDebt = Money.parseOrNull(debt)!!,
            assetsTotal = Money.parseOrNull(assets)!!,
            hasAssets = hasAssets,
            loading = false
        )

    @Test
    fun `with no assets, net worth is the debt negated`() {
        // The honest figure when nothing is owned yet, and the reason the card
        // shows a caption explaining why the two halves match.
        val s = state(debt = "91937.05")
        assertEquals(Money.parseOrNull("-91937.05"), s.netWorth)
        assertTrue(s.netWorth.isNegative)
        assertFalse(s.hasAssets)
    }

    @Test
    fun `assets below debt leave it negative`() {
        assertEquals(
            Money.parseOrNull("-41937.05"),
            state(debt = "91937.05", assets = "50000.00", hasAssets = true).netWorth
        )
    }

    @Test
    fun `assets above debt turn it positive`() {
        val s = state(debt = "91937.05", assets = "150000.00", hasAssets = true)
        assertEquals(Money.parseOrNull("58062.95"), s.netWorth)
        assertTrue(s.netWorth.isPositive)
    }

    @Test
    fun `exactly break even is zero, neither positive nor negative`() {
        // The boundary that would have made a threshold-switching headline flip
        // back and forth. Showing both halves always avoids that entirely.
        val s = state(debt = "91937.05", assets = "91937.05", hasAssets = true)
        assertEquals(Money.ZERO, s.netWorth)
        assertFalse(s.netWorth.isPositive)
        assertFalse(s.netWorth.isNegative)
    }

    @Test
    fun `no debt and no assets is zero rather than an error`() {
        val s = DashboardUiState(loading = false)
        assertEquals(Money.ZERO, s.netWorth)
        assertEquals(Money.ZERO, s.totalDebt)
    }

    @Test
    fun `assets with no debt is simply the assets`() {
        assertEquals(
            Money.parseOrNull("12500.00"),
            state(debt = "0.00", assets = "12500.00", hasAssets = true).netWorth
        )
    }

    @Test
    fun `it stays exact to the cent`() {
        // Integer cents throughout, so no rounding creeps in on subtraction.
        assertEquals(
            Money.parseOrNull("-0.01"),
            state(debt = "1254.64", assets = "1254.63", hasAssets = true).netWorth
        )
    }
}
