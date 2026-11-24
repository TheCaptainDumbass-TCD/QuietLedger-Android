package com.quietwire.quietledger.ui.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.SavingsPurpose
import com.quietwire.quietledger.data.repository.DebtRepository
import com.quietwire.quietledger.data.repository.RecurringExpenseRepository
import kotlinx.coroutines.flow.combine
import com.quietwire.quietledger.data.repository.SavingsRepository
import com.quietwire.quietledger.domain.model.SavingsAccount
import com.quietwire.quietledger.ui.common.resilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One rung of the emergency fund: a target and how far along it is. */
data class EmergencyFundTarget(
    val months: Int,
    val target: Money,
    val saved: Money
) {
    val shortfall: Money get() = (target - saved).takeIf { it.isPositive } ?: Money.ZERO
    val reached: Boolean get() = saved >= target

    /** Capped at 1 so a bar cannot overrun; [reached] says if it was exceeded. */
    val progress: Float
        get() = if (!target.isPositive) 0f
        else (saved.minorUnits.toFloat() / target.minorUnits.toFloat()).coerceIn(0f, 1f)
}

data class SavingsUiState(
    val accounts: List<SavingsAccount> = emptyList(),
    val total: Money = Money.ZERO,
    val projectedAnnualInterest: Money = Money.ZERO,
    /**
     * What one month actually costs: every recurring expense plus every debt
     * payment.
     *
     * Debt payments are included deliberately. They are what leaves the account
     * each month, and missing them has consequences well beyond a late fee.
     * Sizing against expenses alone would describe surviving, not continuing.
     */
    val monthlyNeed: Money = Money.ZERO,
    val loading: Boolean = true,
    /** Could not read the database. Not the same as having no savings. */
    val unavailable: Boolean = false
) {
    /** The usual rungs. Empty until there is a monthly figure to size against. */
    val emergencyFund: List<EmergencyFundTarget>
        get() = if (!monthlyNeed.isPositive) emptyList()
        else listOf(1, 3, 6).map {
            EmergencyFundTarget(months = it, target = monthlyNeed * it, saved = total)
        }

    /** Whole months of cover the savings currently buy. */
    val monthsCovered: Float
        get() = if (!monthlyNeed.isPositive) 0f
        else total.minorUnits.toFloat() / monthlyNeed.minorUnits.toFloat()
}

data class SavingsEditorState(
    val id: Long = 0,
    val name: String = "",
    val institution: String = "",
    val balance: String = "",
    val apy: String = "",
    val purpose: SavingsPurpose = SavingsPurpose.GENERAL,
    val goal: String = "",
    val notes: String = ""
) {
    val isEditing: Boolean get() = id != 0L

    val parsedBalance: Money? get() = Money.parseOrNull(balance)
    val parsedGoal: Money?
        get() = goal.trim().takeIf { it.isNotEmpty() }?.let { Money.parseOrNull(it) }

    val parsedApy: Int?
        get() = apy.trim().takeIf { it.isNotEmpty() }
            ?.toDoubleOrNull()
            ?.let { Math.round(it * 100).toInt() }

    val nameError: String? get() = if (name.isBlank()) "Give it a name" else null

    val balanceError: String?
        get() = when {
            balance.isBlank() -> "Enter a balance"
            parsedBalance == null -> "That is not an amount"
            // Zero is fine, negative is not: it would quietly reduce net worth
            // as though the account were a debt.
            parsedBalance!!.isNegative -> "A savings balance cannot be negative"
            else -> null
        }

    val apyError: String?
        get() = when {
            apy.isBlank() -> null
            parsedApy == null -> "That is not a rate"
            parsedApy!! < 0 -> "Cannot be negative"
            else -> null
        }

    val goalError: String?
        get() = when {
            goal.isBlank() -> null
            parsedGoal == null -> "That is not an amount"
            !parsedGoal!!.isPositive -> "A goal has to be more than nothing"
            else -> null
        }

    val isValid: Boolean
        get() = nameError == null && balanceError == null &&
            apyError == null && goalError == null

    /** Interest for a year at this rate, shown live as the rate is typed. */
    val projectedInterest: Money?
        get() {
            val balance = parsedBalance ?: return null
            val rate = parsedApy?.takeIf { it > 0 } ?: return null
            return Money.ofMinor(balance.minorUnits * rate / 10_000)
        }
}

data class MovementState(
    val accountId: Long,
    val accountName: String,
    val isDeposit: Boolean,
    val amount: String = "",
    val note: String = ""
) {
    val parsedAmount: Money? get() = Money.parseOrNull(amount)
    val isValid: Boolean get() = parsedAmount?.isPositive == true
}

@HiltViewModel
class SavingsViewModel @Inject constructor(
    private val repository: SavingsRepository,
    expenses: RecurringExpenseRepository,
    debts: DebtRepository
) : ViewModel() {

    val uiState: StateFlow<SavingsUiState> = combine(
        repository.observeOpen(),
        expenses.observeMonthlyTotal(),
        debts.observeOpen()
    ) { accounts, monthlyExpenses, openDebts ->
        val monthlyDebt = openDebts.fold(Money.ZERO) { a, d -> a + d.monthlyPayment }
        SavingsUiState(
            accounts = accounts,
            total = accounts.fold(Money.ZERO) { a, acc -> a + acc.currentBalance },
            projectedAnnualInterest = accounts.fold(Money.ZERO) { a, acc ->
                a + (acc.projectedAnnualInterest ?: Money.ZERO)
            },
            monthlyNeed = monthlyExpenses + monthlyDebt,
            loading = false
        )
    }
        .resilient(SavingsUiState(loading = false, unavailable = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavingsUiState())

    private val _editor = MutableStateFlow<SavingsEditorState?>(null)
    val editor: StateFlow<SavingsEditorState?> = _editor.asStateFlow()

    private val _movement = MutableStateFlow<MovementState?>(null)
    val movement: StateFlow<MovementState?> = _movement.asStateFlow()

    fun startAdding() {
        _editor.value = SavingsEditorState()
    }

    fun startEditing(account: SavingsAccount) {
        _editor.value = SavingsEditorState(
            id = account.id,
            name = account.name,
            institution = account.institution,
            balance = account.currentBalance.toPlainString(),
            apy = account.apyBasisPoints?.let { (it / 100.0).toString() } ?: "",
            purpose = account.purpose,
            goal = account.goal?.toPlainString() ?: "",
            notes = account.notes
        )
    }

    fun updateEditor(transform: (SavingsEditorState) -> SavingsEditorState) {
        _editor.value = _editor.value?.let(transform)
    }

    fun cancelEditing() {
        _editor.value = null
    }

    fun save() {
        val form = _editor.value ?: return
        if (!form.isValid) return

        val account = SavingsAccount(
            id = form.id,
            name = form.name,
            institution = form.institution,
            currentBalance = form.parsedBalance ?: return,
            apyBasisPoints = form.parsedApy,
            purpose = form.purpose,
            goal = form.parsedGoal,
            notes = form.notes
        )

        viewModelScope.launch {
            if (form.isEditing) repository.update(account) else repository.add(account)
            _editor.value = null
        }
    }

    /** Removes the account and its history outright. */
    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            _editor.value = null
        }
    }

    fun close(id: Long) {
        viewModelScope.launch { repository.close(id) }
    }

    /**
     * A deposit or withdrawal, rather than making the user work out the new
     * balance themselves.
     *
     * The journal then reads as events instead of a list of balances, which is
     * what makes a balance that fell an answerable question later.
     */
    fun startMovement(account: SavingsAccount, deposit: Boolean) {
        _movement.value = MovementState(
            accountId = account.id,
            accountName = account.name,
            isDeposit = deposit
        )
    }

    fun updateMovement(transform: (MovementState) -> MovementState) {
        _movement.value = _movement.value?.let(transform)
    }

    fun cancelMovement() {
        _movement.value = null
    }

    fun confirmMovement() {
        val state = _movement.value ?: return
        val amount = state.parsedAmount ?: return
        if (!amount.isPositive) return

        viewModelScope.launch {
            repository.recordMovement(
                id = state.accountId,
                amount = if (state.isDeposit) amount else -amount,
                note = state.note.trim().takeIf { it.isNotEmpty() }
            )
            _movement.value = null
        }
    }
}
