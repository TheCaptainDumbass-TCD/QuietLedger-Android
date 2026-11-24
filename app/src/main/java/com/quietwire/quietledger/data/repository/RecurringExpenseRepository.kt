package com.quietwire.quietledger.data.repository

import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.entity.RecurringExpenseEntity
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.ExpenseGroup
import com.quietwire.quietledger.data.model.RecurringExpenseCategory
import com.quietwire.quietledger.data.model.SubjectType
import com.quietwire.quietledger.domain.model.RecurringExpense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recurring expenses: rent, utilities, insurance, anything that repeats and has
 * no balance.
 *
 * Kept entirely separate from debts, which is the main structural improvement
 * over the desktop app. There, both live in one table and are told apart by two
 * hardcoded category sets, so every single query has to remember to filter and
 * one that forgets quietly puts your electricity bill into the snowball
 * calculation.
 *
 * Nothing in this file is reachable from the payoff engine, by design. A
 * recurring expense has no end, so including one would make the debt-free date
 * meaningless.
 */
@Singleton
class RecurringExpenseRepository @Inject constructor(
    private val databases: DatabaseHolder
) {

    private val dao get() = databases.require().recurringExpenseDao()
    private val changes get() = databases.require().balanceChangeDao()

    /**
     * Resolves the database per subscription and follows it across a swap.
     *
     * Not `flow { emitAll(block(databases.require())) }`: that throws while
     * locked, and the throw used to be caught in the ViewModel and rendered as
     * "you have no data". Waiting for the database instead means a momentary
     * lock produces no emission rather than a false empty state, and unlocking
     * re-subscribes automatically.
     */
    private fun <T> fromDatabase(block: (QuietLedgerDatabase) -> Flow<T>): Flow<T> =
        databases.observe().filterNotNull().flatMapLatest { block(it) }

    fun observeActive(): Flow<List<RecurringExpense>> =
        fromDatabase { it.recurringExpenseDao().observeActive() }
            .map { list -> list.map { e -> e.toDomain() } }

    fun observeAll(): Flow<List<RecurringExpense>> =
        fromDatabase { it.recurringExpenseDao().observeAll() }
            .map { list -> list.map { e -> e.toDomain() } }

    /** Monthly equivalent of everything active, for the dashboard. */
    fun observeMonthlyTotal(): Flow<Money> =
        observeActive().map { expenses ->
            expenses.fold(Money.ZERO) { total, e -> total + e.monthlyAmount }
        }

    /** Monthly equivalent per group, which is what reporting wants. */
    fun observeMonthlyByGroup(): Flow<Map<ExpenseGroup, Money>> =
        observeActive().map { expenses ->
            expenses
                .groupBy { it.category.group }
                .mapValues { (_, items) ->
                    items.fold(Money.ZERO) { total, e -> total + e.monthlyAmount }
                }
                .toList()
                .sortedByDescending { it.second.minorUnits }
                .toMap()
        }

    suspend fun getActive(): List<RecurringExpense> = dao.getActive().map { it.toDomain() }

    suspend fun add(expense: RecurringExpense): Long {
        val now = Instant.now().toEpochMilli()
        val id = dao.insert(
            RecurringExpenseEntity(
                name = expense.name.trim(),
                category = expense.category.name,
                amountMinor = expense.amount.minorUnits,
                frequency = expense.frequency.name,
                dueDayOfMonth = expense.dueDayOfMonth,
                isActive = true,
                notes = expense.notes.trim(),
                sortOrder = dao.nextSortOrder(),
                createdAt = now,
                updatedAt = now
            )
        )

        changes.insert(
            com.quietwire.quietledger.data.db.entity.BalanceChangeEntity(
                subjectType = SubjectType.RECURRING_EXPENSE.name,
                subjectId = id,
                field = FIELD_AMOUNT,
                previousMinor = null,
                newMinor = expense.amount.minorUnits,
                deltaMinor = expense.amount.minorUnits,
                source = ChangeSource.INITIAL.name,
                subjectName = expense.name.trim(),
                note = null,
                occurredAt = now
            )
        )
        return id
    }

    /** Journals the amount only when it actually changed, same as debts. */
    suspend fun update(expense: RecurringExpense) {
        val existing = dao.getById(expense.id) ?: return
        val now = Instant.now().toEpochMilli()

        dao.update(
            existing.copy(
                name = expense.name.trim(),
                category = expense.category.name,
                amountMinor = expense.amount.minorUnits,
                frequency = expense.frequency.name,
                dueDayOfMonth = expense.dueDayOfMonth,
                notes = expense.notes.trim(),
                updatedAt = now
            )
        )

        if (existing.amountMinor != expense.amount.minorUnits) {
            changes.insert(
                com.quietwire.quietledger.data.db.entity.BalanceChangeEntity(
                    subjectType = SubjectType.RECURRING_EXPENSE.name,
                    subjectId = expense.id,
                    field = FIELD_AMOUNT,
                    previousMinor = existing.amountMinor,
                    newMinor = expense.amount.minorUnits,
                    deltaMinor = expense.amount.minorUnits - existing.amountMinor,
                    source = ChangeSource.MANUAL.name,
                    subjectName = expense.name.trim(),
                    note = null,
                    occurredAt = now
                )
            )
        }
    }

    /**
     * Ends an expense rather than deleting it, so the history of what was paid
     * stays intact. Cancelling a subscription should not rewrite last year.
     */
    suspend fun end(id: Long) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                isActive = false,
                endedAt = Instant.now().toEpochMilli(),
                updatedAt = Instant.now().toEpochMilli()
            )
        )
    }

    suspend fun reactivate(id: Long) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.copy(isActive = true, endedAt = null))
    }

    /** Only for a mistaken entry. Removes the journal with it. */
    suspend fun delete(id: Long) {
        changes.deleteFor(SubjectType.RECURRING_EXPENSE.name, id)
        dao.deleteById(id)
    }

    fun observeHistory(id: Long) =
        fromDatabase { it.balanceChangeDao().observeFor(SubjectType.RECURRING_EXPENSE.name, id) }

    companion object {
        const val FIELD_AMOUNT = "amount"
    }
}

private fun RecurringExpenseEntity.toDomain() = RecurringExpense(
    id = id,
    name = name,
    category = RecurringExpenseCategory.fromStored(category),
    amount = Money.ofMinor(amountMinor),
    frequency = Frequency.fromStored(frequency),
    dueDayOfMonth = dueDayOfMonth,
    isActive = isActive,
    endedAt = endedAt?.let { Instant.ofEpochMilli(it) },
    notes = notes
)
