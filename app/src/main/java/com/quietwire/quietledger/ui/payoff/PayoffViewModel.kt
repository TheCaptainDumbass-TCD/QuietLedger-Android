package com.quietwire.quietledger.ui.payoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.repository.DebtRepository
import com.quietwire.quietledger.domain.payoff.PayoffCalculator
import com.quietwire.quietledger.domain.payoff.PayoffComparison
import com.quietwire.quietledger.ui.common.resilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PayoffUiState(
    val comparison: PayoffComparison? = null,
    val extraPayment: String = "",
    /**
     * Off by default. Rolling cleared payments into the next debt assumes that
     * money keeps going to debt, which is the user's decision to make, not an
     * assumption to bake into their debt-free date.
     */
    val rolloverEnabled: Boolean = false,
    val loading: Boolean = true,
    /** Could not read the database. Not the same as having no plan. */
    val unavailable: Boolean = false,
    val hasDebts: Boolean = false
)

@HiltViewModel
class PayoffViewModel @Inject constructor(
    repository: DebtRepository,
    private val calculator: PayoffCalculator
) : ViewModel() {

    private val extra = MutableStateFlow("")
    val extraPayment: StateFlow<String> = extra.asStateFlow()

    private val rollover = MutableStateFlow(false)

    val uiState: StateFlow<PayoffUiState> =
        combine(repository.observeOpen(), extra, rollover) { debts, extraText, rolloverOn ->
            val extraMoney = Money.parseOrNull(extraText) ?: Money.ZERO
            PayoffUiState(
                comparison = if (debts.isEmpty()) {
                    null
                } else {
                    calculator.compare(debts, extraMonthlyPayment = extraMoney)
                },
                extraPayment = extraText,
                rolloverEnabled = rolloverOn,
                loading = false,
                hasDebts = debts.isNotEmpty()
            )
        }
        .resilient(PayoffUiState(loading = false, unavailable = true))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PayoffUiState())

    fun setExtraPayment(value: String) { extra.value = value }

    fun setRolloverEnabled(enabled: Boolean) { rollover.value = enabled }
}
