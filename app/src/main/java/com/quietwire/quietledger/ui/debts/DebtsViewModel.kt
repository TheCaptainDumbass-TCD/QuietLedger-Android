package com.quietwire.quietledger.ui.debts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.DebtCategory
import com.quietwire.quietledger.data.repository.DebtRepository
import com.quietwire.quietledger.domain.model.BalanceChange
import com.quietwire.quietledger.domain.model.Debt
import com.quietwire.quietledger.ui.common.resilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebtsUiState(
    val debts: List<Debt> = emptyList(),
    val totalOutstanding: Money = Money.ZERO,
    val totalMonthly: Money = Money.ZERO,
    val loading: Boolean = true,
    /** Could not read the database. Not the same as having no debts. */
    val unavailable: Boolean = false
)

/** Form state for the add and edit sheet, kept as text so partial input is allowed. */
data class DebtEditorState(
    val id: Long = 0,
    val name: String = "",
    val category: DebtCategory = DebtCategory.CREDIT_CARD,
    val balance: String = "",
    val payment: String = "",
    val frequency: Frequency = Frequency.MONTHLY,
    val apr: String = "",
    /** The rate is an introductory offer that will end. */
    val aprIsPromotional: Boolean = false,
    val notes: String = ""
) {
    val isEditing: Boolean get() = id != 0L
    val parsedBalance: Money? get() = Money.parseOrNull(balance)
    val parsedPayment: Money? get() = if (payment.isBlank()) Money.ZERO else Money.parseOrNull(payment)
    val parsedApr: Int?
        get() = apr.trim().takeIf { it.isNotEmpty() }
            ?.toDoubleOrNull()
            ?.let { Math.round(it * 100).toInt() }

    val nameError: String? get() = if (name.isBlank()) "Give it a name" else null
    val balanceError: String?
        get() = when {
            balance.isBlank() -> "Enter the balance"
            parsedBalance == null -> "That is not an amount"
            parsedBalance!!.isNegative -> "A balance cannot be negative"
            else -> null
        }
    val paymentError: String?
        get() = when {
            payment.isNotBlank() && parsedPayment == null -> "That is not an amount"
            parsedPayment?.isNegative == true -> "A payment cannot be negative"
            else -> null
        }
    val aprError: String?
        get() = if (apr.isNotBlank() && apr.toDoubleOrNull() == null) "That is not a rate" else null

    val isValid: Boolean
        get() = nameError == null && balanceError == null &&
            paymentError == null && aprError == null

    companion object {
        fun from(debt: Debt) = DebtEditorState(
            id = debt.id,
            name = debt.name,
            category = debt.category,
            balance = debt.currentBalance.toPlainString(),
            payment = debt.effectivePayment.takeIf { it.isPositive }?.toPlainString() ?: "",
            frequency = debt.paymentFrequency,
            apr = debt.aprBasisPoints?.let { (it / 100.0).toString() } ?: "",
            aprIsPromotional = debt.aprIsPromotional,
            notes = debt.notes
        )
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DebtsViewModel @Inject constructor(
    private val repository: DebtRepository
) : ViewModel() {

    val uiState: StateFlow<DebtsUiState> = repository.observeOpen()
        .map { debts ->
            DebtsUiState(
                debts = debts,
                totalOutstanding = debts.fold(Money.ZERO) { a, d -> a + d.currentBalance },
                totalMonthly = debts.fold(Money.ZERO) { a, d -> a + d.monthlyPayment },
                loading = false
            )
        }
        .resilient(DebtsUiState(loading = false, unavailable = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtsUiState())

    private val _editor = MutableStateFlow<DebtEditorState?>(null)
    val editor: StateFlow<DebtEditorState?> = _editor.asStateFlow()

    /**
     * History of the debt currently open in the editor. Follows the selection
     * rather than being fetched once, so a payment recorded now appears
     * immediately.
     */
    val history: StateFlow<List<BalanceChange>> = _editor
        .map { it?.id ?: 0L }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList()) else repository.observeHistory(id)
        }
        // History is supplementary, so an empty list is an acceptable
        // degradation here. Still resilient rather than caught, because `catch`
        // would end the flow and the history would then stay empty for the rest
        // of the screen's life even once the database came back.
        .resilient(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startAdding() { _editor.value = DebtEditorState() }

    fun startEditing(debt: Debt) { _editor.value = DebtEditorState.from(debt) }

    fun updateEditor(transform: (DebtEditorState) -> DebtEditorState) {
        _editor.value = _editor.value?.let(transform)
    }

    fun cancelEditing() { _editor.value = null }

    fun save() {
        val form = _editor.value ?: return
        if (!form.isValid) return

        viewModelScope.launch {
            if (form.isEditing) {
                val existing = repository.getById(form.id) ?: return@launch
                repository.update(
                    existing.copy(
                        name = form.name.trim(),
                        category = form.category,
                        currentBalance = form.parsedBalance!!,
                        scheduledPayment = form.parsedPayment ?: Money.ZERO,
                        paymentFrequency = form.frequency,
                        aprBasisPoints = form.parsedApr,
                        // Meaningless without a rate, so it cannot linger
                        // after someone clears the APR field.
                        aprIsPromotional = form.aprIsPromotional && form.parsedApr != null,
                        notes = form.notes.trim()
                    )
                )
            } else {
                repository.add(
                    Debt(
                        name = form.name.trim(),
                        category = form.category,
                        currentBalance = form.parsedBalance!!,
                        scheduledPayment = form.parsedPayment ?: Money.ZERO,
                        paymentFrequency = form.frequency,
                        aprBasisPoints = form.parsedApr,
                        // Meaningless without a rate, so it cannot linger
                        // after someone clears the APR field.
                        aprIsPromotional = form.aprIsPromotional && form.parsedApr != null,
                        notes = form.notes.trim()
                    )
                )
            }
            _editor.value = null
        }
    }

    fun recordPayment(debtId: Long, amount: Money) {
        viewModelScope.launch { repository.recordPayment(debtId, amount) }
    }

    fun delete(debtId: Long) {
        viewModelScope.launch { repository.delete(debtId) }
    }
}
