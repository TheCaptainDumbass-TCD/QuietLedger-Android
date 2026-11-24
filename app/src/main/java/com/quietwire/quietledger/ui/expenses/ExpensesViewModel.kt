package com.quietwire.quietledger.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.ExpenseGroup
import com.quietwire.quietledger.data.model.RecurringExpenseCategory
import com.quietwire.quietledger.data.repository.RecurringExpenseRepository
import com.quietwire.quietledger.domain.model.RecurringExpense
import com.quietwire.quietledger.ui.common.resilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExpensesUiState(
    val groups: List<ExpenseGroupSection> = emptyList(),
    val monthlyTotal: Money = Money.ZERO,
    val annualTotal: Money = Money.ZERO,
    val count: Int = 0,
    val loading: Boolean = true,
    /** Could not read the database. Not the same as having no expenses. */
    val unavailable: Boolean = false
)

/** One group with its members and subtotal, ready to render. */
data class ExpenseGroupSection(
    val group: ExpenseGroup,
    val expenses: List<RecurringExpense>,
    val monthlySubtotal: Money
)

data class ExpenseEditorState(
    val id: Long = 0,
    val name: String = "",
    val category: RecurringExpenseCategory = RecurringExpenseCategory.RENT,
    val amount: String = "",
    val frequency: Frequency = Frequency.MONTHLY,
    val dueDay: String = "",
    val notes: String = ""
) {
    val isEditing: Boolean get() = id != 0L

    val parsedAmount: Money? get() = Money.parseOrNull(amount)

    val nameError: String? get() = if (name.isBlank()) "Give it a name" else null

    val amountError: String?
        get() = when {
            amount.isBlank() -> "Enter an amount"
            parsedAmount == null -> "That is not an amount"
            parsedAmount!!.isNegative -> "Cannot be negative"
            else -> null
        }

    val dueDayError: String?
        get() {
            if (dueDay.isBlank()) return null
            val day = dueDay.toIntOrNull() ?: return "Use a day between 1 and 31"
            return if (day in 1..31) null else "Use a day between 1 and 31"
        }

    val isValid: Boolean
        get() = nameError == null && amountError == null && dueDayError == null

    /** What this works out to per month, shown live as the user types. */
    val monthlyEquivalent: Money?
        get() = parsedAmount?.takeIf { frequency != Frequency.MONTHLY }
            ?.let { frequency.monthlyEquivalent(it) }
}

@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val repository: RecurringExpenseRepository
) : ViewModel() {

    val uiState: StateFlow<ExpensesUiState> = repository.observeActive()
        .map { expenses ->
            val sections = expenses
                .groupBy { it.category.group }
                .toList()
                .sortedBy { (group, _) -> group.ordinal }
                .map { (group, items) ->
                    ExpenseGroupSection(
                        group = group,
                        expenses = items.sortedByDescending { it.monthlyAmount.minorUnits },
                        monthlySubtotal = items.fold(Money.ZERO) { a, e -> a + e.monthlyAmount }
                    )
                }

            ExpensesUiState(
                groups = sections,
                monthlyTotal = expenses.fold(Money.ZERO) { a, e -> a + e.monthlyAmount },
                annualTotal = expenses.fold(Money.ZERO) { a, e -> a + e.annualAmount },
                count = expenses.size,
                loading = false
            )
        }
        .resilient(ExpensesUiState(loading = false, unavailable = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUiState())

    private val _editor = MutableStateFlow<ExpenseEditorState?>(null)
    val editor: StateFlow<ExpenseEditorState?> = _editor.asStateFlow()

    fun startAdding() {
        _editor.value = ExpenseEditorState()
    }

    fun startEditing(expense: RecurringExpense) {
        _editor.value = ExpenseEditorState(
            id = expense.id,
            name = expense.name,
            category = expense.category,
            amount = expense.amount.toPlainString(),
            frequency = expense.frequency,
            dueDay = expense.dueDayOfMonth?.toString() ?: "",
            notes = expense.notes
        )
    }

    fun updateEditor(transform: (ExpenseEditorState) -> ExpenseEditorState) {
        _editor.value = _editor.value?.let(transform)
    }

    fun cancelEditing() {
        _editor.value = null
    }

    fun save() {
        val form = _editor.value ?: return
        if (!form.isValid) return

        val expense = RecurringExpense(
            id = form.id,
            name = form.name,
            category = form.category,
            amount = form.parsedAmount ?: return,
            frequency = form.frequency,
            dueDayOfMonth = form.dueDay.toIntOrNull(),
            notes = form.notes
        )

        viewModelScope.launch {
            if (form.isEditing) repository.update(expense) else repository.add(expense)
            _editor.value = null
        }
    }

    /**
     * Ends rather than deletes, so what was paid historically stays readable.
     * Cancelling a subscription today should not rewrite last year.
     */
    /** Removes the entry outright. For something added in error. */
    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            _editor.value = null
        }
    }

    fun end(id: Long) {
        viewModelScope.launch { repository.end(id) }
    }
}
