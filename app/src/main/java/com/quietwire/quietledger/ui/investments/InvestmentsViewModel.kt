package com.quietwire.quietledger.ui.investments

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.importer.CsvTooLargeException
import com.quietwire.quietledger.data.importer.FidelityPositionsParser
import com.quietwire.quietledger.data.repository.HoldingsImportResult
import com.quietwire.quietledger.data.repository.HoldingsRepository
import com.quietwire.quietledger.domain.model.Holding
import com.quietwire.quietledger.ui.common.resilient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class InvestmentsUiState(
    val holdings: List<Holding> = emptyList(),
    val total: Money = Money.ZERO,
    val asOf: Instant? = null,
    val loading: Boolean = true,
    val unavailable: Boolean = false
) {
    val positions: List<Holding> get() = holdings.filter { !it.isCash }
    val cash: List<Holding> get() = holdings.filter { it.isCash }

    /**
     * Holdings grouped by the account they sit in, largest first.
     *
     * The export can contain several accounts, and they are not equivalent: an
     * IRA and a taxable brokerage account differ in how the money can be used
     * and taxed. Summing them into one figure without saying so would make the
     * total mean less than it looks.
     *
     * Positions before cash within each account, so a money-market balance does
     * not sit above actual holdings.
     */
    val byAccount: List<AccountHoldings>
        get() = holdings
            .groupBy { it.accountName }
            .map { (account, held) ->
                AccountHoldings(
                    accountName = account,
                    positions = held.filter { !it.isCash }.sortedByDescending { it.currentValue.minorUnits },
                    cash = held.filter { it.isCash },
                    total = held.fold(Money.ZERO) { a, h -> a + h.currentValue }
                )
            }
            .sortedByDescending { it.total.minorUnits }

    /** More than one account means the grouping is worth showing. */
    val hasMultipleAccounts: Boolean get() = byAccount.size > 1

    val cashTotal: Money
        get() = cash.fold(Money.ZERO) { a, h -> a + h.currentValue }

    /**
     * Combined gain or loss, counting only positions that report a cost basis.
     *
     * Null when none of them do, rather than zero. Zero would read as "broke
     * even", which is a different statement from "not enough information".
     */
    val totalGainLoss: Money?
        get() {
            val reporting = positions.filter { it.gainLoss != null }
            if (reporting.isEmpty()) return null
            return reporting.fold(Money.ZERO) { a, h -> a + h.gainLoss!! }
        }

    val ageInDays: Long? get() = asOf?.let { Holding(accountName = "", symbol = "", asOf = it).ageInDays() }
}

/** One brokerage account and what it holds. */
data class AccountHoldings(
    val accountName: String,
    val positions: List<Holding>,
    val cash: List<Holding>,
    val total: Money
)

sealed interface ImportState {
    data object Idle : ImportState
    data object Working : ImportState
    data class Done(val result: HoldingsImportResult) : ImportState
    data class Failed(val message: String) : ImportState
}

@HiltViewModel
class InvestmentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holdings: HoldingsRepository
) : ViewModel() {

    val uiState: StateFlow<InvestmentsUiState> = combine(
        holdings.observeAll(),
        holdings.observeTotal(),
        holdings.observeAsOf()
    ) { list, total, asOf ->
        InvestmentsUiState(holdings = list, total = total, asOf = asOf, loading = false)
    }
        .resilient(InvestmentsUiState(loading = false, unavailable = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvestmentsUiState())

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    /** Mime types a Fidelity export arrives as, depending on where it came from. */
    val pickerTypes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")

    fun importFrom(uri: Uri) {
        _import.value = ImportState.Working
        viewModelScope.launch {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: error("That file could not be opened.")

                holdings.applyImport(FidelityPositionsParser.parse(text))
            }.onSuccess {
                _import.value = ImportState.Done(it)
            }.onFailure { error ->
                _import.value = ImportState.Failed(
                    when (error) {
                        // The parser's own messages are written for a person, so
                        // they are passed through rather than replaced.
                        is CsvTooLargeException -> error.message ?: "That file is too large."
                        is IllegalArgumentException -> error.message
                            ?: "That did not look like a positions export."
                        else -> error.message ?: "The import failed."
                    }
                )
            }
        }
    }

    fun dismissImport() {
        _import.value = ImportState.Idle
    }
}
