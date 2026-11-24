package com.quietwire.quietledger.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.repository.DebtRepository
import com.quietwire.quietledger.data.repository.HoldingsRepository
import com.quietwire.quietledger.data.repository.MetalsRepository
import com.quietwire.quietledger.data.repository.SavingsRepository
import com.quietwire.quietledger.domain.model.BalanceChange
import com.quietwire.quietledger.domain.model.Debt
import com.quietwire.quietledger.domain.model.SavingsAccount
import com.quietwire.quietledger.domain.payoff.PayoffCalculator
import com.quietwire.quietledger.domain.payoff.PayoffPlan
import com.quietwire.quietledger.ui.common.resilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardUiState(
    val totalDebt: Money = Money.ZERO,
    val monthlyOutlay: Money = Money.ZERO,
    val debtCount: Int = 0,
    val plan: PayoffPlan = PayoffPlan.EMPTY,
    val largestDebt: Debt? = null,
    val nextToClear: Debt? = null,
    val recentActivity: List<BalanceChange> = emptyList(),
    /** Debts whose rate is an introductory offer that will end. */
    val promotionalCount: Int = 0,
    /**
     * Everything owned: investments, cash, savings, metals.
     *
     * Zero until those screens exist. Net worth is still shown rather than
     * hidden, because with nothing recorded a negative net worth equal to the
     * debt is the truthful figure, and the caption says why the two halves
     * match.
     */
    val assetsTotal: Money = Money.ZERO,
    val hasAssets: Boolean = false,
    val savingsCount: Int = 0,
    val savingsTotal: Money = Money.ZERO,
    val investmentsTotal: Money = Money.ZERO,
    val metalsTotal: Money = Money.ZERO,
    /** Whether [metalsTotal] is a market value or what was paid. */
    val metalsAtMarket: Boolean = false,
    /** What was paid, so the card can colour itself by gain. */
    val metalsCost: Money = Money.ZERO,
    val loading: Boolean = true,
    /** Could not read the database. Not the same as having no data. */
    val unavailable: Boolean = false
) {
    /**
     * Gain on the metals, or null when they are only valued at cost.
     *
     * Null rather than zero: without a spot price there is no gain to report,
     * and zero would read as having exactly broken even.
     */
    val metalsGain: Money? get() = if (metalsAtMarket) metalsTotal - metalsCost else null

    /** Assets minus liabilities. Negative is a perfectly valid answer. */
    val netWorth: Money get() = assetsTotal - totalDebt
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: DebtRepository,
    savings: SavingsRepository,
    holdings: HoldingsRepository,
    metals: MetalsRepository,
    calculator: PayoffCalculator
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeOpen(),
        repository.observeRecentActivity(limit = 8),
        savings.observeTotal(),
        savings.observeOpen(),
        holdings.observeTotal(),
        metals.observeTotalCost(),
        metals.observeMarketValue()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val debts = values[0] as List<Debt>
        @Suppress("UNCHECKED_CAST")
        val activity = values[1] as List<BalanceChange>
        val savingsTotal = values[2] as Money
        @Suppress("UNCHECKED_CAST")
        val savingsAccounts = values[3] as List<SavingsAccount>
        val investmentsTotal = values[4] as Money
        val metalsCost = values[5] as Money
        @Suppress("UNCHECKED_CAST")
        val metalsMarket = values[6] as Money?

        // Market value once every held metal has a price, otherwise cost.
        // Never a mixture: part at market and part at cost is a figure that
        // cannot be checked against anything.
        val metalsTotal = metalsMarket ?: metalsCost
            val plan = calculator.project(debts)
            DashboardUiState(
                totalDebt = debts.fold(Money.ZERO) { a, d -> a + d.currentBalance },
                monthlyOutlay = debts.fold(Money.ZERO) { a, d -> a + d.monthlyPayment },
                debtCount = debts.size,
                plan = plan,
                largestDebt = debts.maxByOrNull { it.currentBalance.minorUnits },
                nextToClear = plan.projections
                    .filter { it.willClear }
                    .minByOrNull { it.payoffOrder ?: Int.MAX_VALUE }
                    ?.debt,
                recentActivity = activity,
                promotionalCount = debts.count { it.aprIsPromotional },
                // Everything owned. Savings is a known balance; investments
                // come from the broker's own positions export, which is why
                // this works without a network permission.
                assetsTotal = savingsTotal + investmentsTotal + metalsTotal,
                savingsTotal = savingsTotal,
                investmentsTotal = investmentsTotal,
                metalsTotal = metalsTotal,
                metalsAtMarket = metalsMarket != null,
                metalsCost = metalsCost,
                hasAssets = savingsAccounts.isNotEmpty() || investmentsTotal.isPositive ||
                    metalsTotal.isPositive,
                savingsCount = savingsAccounts.size,
                loading = false
            )
        }
        .resilient(DashboardUiState(loading = false, unavailable = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
