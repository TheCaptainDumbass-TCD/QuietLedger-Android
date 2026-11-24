package com.quietwire.quietledger.data.repository

import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.data.db.entity.BalanceChangeEntity
import com.quietwire.quietledger.data.db.entity.SavingsAccountEntity
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.SavingsPurpose
import com.quietwire.quietledger.data.model.SubjectType
import com.quietwire.quietledger.domain.model.SavingsAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Savings accounts, the first thing this app counts as an asset.
 *
 * Every balance change is journalled, same as debts. For savings that history is
 * arguably more useful than for debt: a balance that fell is the question worth
 * asking about, and without a record there is nothing to ask against.
 */
@Singleton
class SavingsRepository @Inject constructor(
    private val databases: DatabaseHolder
) {

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

    fun observeOpen(): Flow<List<SavingsAccount>> =
        fromDatabase { it.savingsAccountDao().observeOpen() }
            .map { list -> list.map { a -> a.toDomain() } }

    fun observeAll(): Flow<List<SavingsAccount>> =
        fromDatabase { it.savingsAccountDao().observeAll() }
            .map { list -> list.map { a -> a.toDomain() } }

    /** Total held, which is the savings contribution to net worth. */
    fun observeTotal(): Flow<Money> =
        fromDatabase { it.savingsAccountDao().observeTotal() }.map { Money.ofMinor(it) }

    suspend fun getOpen(): List<SavingsAccount> =
        databases.require().savingsAccountDao().getOpen().map { it.toDomain() }

    suspend fun add(account: SavingsAccount): Long {
        val db = databases.require()
        val now = Instant.now().toEpochMilli()

        val id = db.savingsAccountDao().insert(
            SavingsAccountEntity(
                name = account.name.trim(),
                institution = account.institution.trim(),
                currentBalanceMinor = account.currentBalance.minorUnits,
                apyBasisPoints = account.apyBasisPoints,
                purpose = account.purpose.name,
                goalMinor = account.goal?.minorUnits,
                notes = account.notes.trim(),
                sortOrder = db.savingsAccountDao().nextSortOrder(),
                createdAt = now,
                updatedAt = now
            )
        )

        db.balanceChangeDao().insert(
            BalanceChangeEntity(
                subjectType = SubjectType.SAVINGS_ACCOUNT.name,
                subjectId = id,
                field = FIELD_BALANCE,
                previousMinor = null,
                newMinor = account.currentBalance.minorUnits,
                deltaMinor = account.currentBalance.minorUnits,
                source = ChangeSource.INITIAL.name,
                subjectName = account.name.trim(),
                note = null,
                occurredAt = now
            )
        )
        return id
    }

    suspend fun update(account: SavingsAccount) {
        val db = databases.require()
        val existing = db.savingsAccountDao().getById(account.id) ?: return
        val now = Instant.now().toEpochMilli()

        db.savingsAccountDao().update(
            existing.copy(
                name = account.name.trim(),
                institution = account.institution.trim(),
                currentBalanceMinor = account.currentBalance.minorUnits,
                apyBasisPoints = account.apyBasisPoints,
                purpose = account.purpose.name,
                goalMinor = account.goal?.minorUnits,
                notes = account.notes.trim(),
                updatedAt = now
            )
        )

        // Only the balance is journalled, and only when it moved. Renaming an
        // account is not a financial event.
        if (existing.currentBalanceMinor != account.currentBalance.minorUnits) {
            db.balanceChangeDao().insert(
                BalanceChangeEntity(
                    subjectType = SubjectType.SAVINGS_ACCOUNT.name,
                    subjectId = account.id,
                    field = FIELD_BALANCE,
                    previousMinor = existing.currentBalanceMinor,
                    newMinor = account.currentBalance.minorUnits,
                    deltaMinor = account.currentBalance.minorUnits - existing.currentBalanceMinor,
                    source = ChangeSource.MANUAL.name,
                    subjectName = account.name.trim(),
                    note = null,
                    occurredAt = now
                )
            )
        }
    }

    /**
     * Records a deposit or withdrawal, rather than making the user work out the
     * new balance themselves.
     *
     * Signed: a negative amount is a withdrawal. The journal entry says which,
     * so the history reads as events rather than a list of balances.
     */
    suspend fun recordMovement(id: Long, amount: Money, note: String? = null) {
        val db = databases.require()
        val existing = db.savingsAccountDao().getById(id) ?: return
        if (amount.isZero) return

        val now = Instant.now().toEpochMilli()
        val updated = existing.currentBalanceMinor + amount.minorUnits

        db.savingsAccountDao().update(
            existing.copy(currentBalanceMinor = updated, updatedAt = now)
        )
        db.balanceChangeDao().insert(
            BalanceChangeEntity(
                subjectType = SubjectType.SAVINGS_ACCOUNT.name,
                subjectId = id,
                field = FIELD_BALANCE,
                previousMinor = existing.currentBalanceMinor,
                newMinor = updated,
                deltaMinor = amount.minorUnits,
                source = ChangeSource.PAYMENT.name,
                subjectName = existing.name,
                note = note,
                occurredAt = now
            )
        )
    }

    /** Closes rather than deletes, so the history stays readable. */
    suspend fun close(id: Long) {
        val db = databases.require()
        val existing = db.savingsAccountDao().getById(id) ?: return
        val now = Instant.now().toEpochMilli()
        db.savingsAccountDao().update(existing.copy(closedAt = now, updatedAt = now))
    }

    suspend fun reopen(id: Long) {
        val db = databases.require()
        val existing = db.savingsAccountDao().getById(id) ?: return
        db.savingsAccountDao().update(existing.copy(closedAt = null))
    }

    /** Only for an entry made in error. Removes its journal with it. */
    suspend fun delete(id: Long) {
        val db = databases.require()
        db.balanceChangeDao().deleteFor(SubjectType.SAVINGS_ACCOUNT.name, id)
        db.savingsAccountDao().deleteById(id)
    }

    fun observeHistory(id: Long) =
        fromDatabase { it.balanceChangeDao().observeFor(SubjectType.SAVINGS_ACCOUNT.name, id) }

    companion object {
        const val FIELD_BALANCE = "balance"
    }
}

private fun SavingsAccountEntity.toDomain() = SavingsAccount(
    id = id,
    name = name,
    institution = institution,
    currentBalance = Money.ofMinor(currentBalanceMinor),
    apyBasisPoints = apyBasisPoints,
    purpose = SavingsPurpose.fromStored(purpose),
    goal = goalMinor?.let { Money.ofMinor(it) },
    closedAt = closedAt?.let { Instant.ofEpochMilli(it) },
    notes = notes,
    sortOrder = sortOrder,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt)
)
