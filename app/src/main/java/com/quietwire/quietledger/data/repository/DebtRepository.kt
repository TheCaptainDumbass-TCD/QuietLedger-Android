package com.quietwire.quietledger.data.repository

import androidx.room.withTransaction
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.entity.BalanceChangeEntity
import com.quietwire.quietledger.data.mapper.toDomain
import com.quietwire.quietledger.data.mapper.toEntity
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.SubjectType
import com.quietwire.quietledger.domain.model.BalanceChange
import com.quietwire.quietledger.domain.model.Debt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes debts, and keeps the change journal honest.
 *
 * Every amount that moves gets a journal row, written in the same
 * transaction as the change itself. If the write fails, both roll back, so
 * the history can never disagree with the data.
 */
@Singleton
class DebtRepository @Inject constructor(
    private val databases: DatabaseHolder,
    private val clock: Clock = Clock.systemUTC()
) {

    private val debts get() = databases.require().debtDao()
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

    fun observeOpen(): Flow<List<Debt>> =
        fromDatabase { it.debtDao().observeOpen() }.map { list -> list.map { d -> d.toDomain() } }

    fun observeAll(): Flow<List<Debt>> =
        fromDatabase { it.debtDao().observeAll() }.map { list -> list.map { d -> d.toDomain() } }

    fun observeById(id: Long): Flow<Debt?> =
        fromDatabase { it.debtDao().observeById(id) }.map { e -> e?.toDomain() }

    fun observeTotalOutstanding(): Flow<Money> =
        fromDatabase { it.debtDao().observeTotalOutstanding() }.map { Money.ofMinor(it) }

    suspend fun getById(id: Long): Debt? = debts.getById(id)?.toDomain()

    suspend fun getOpen(): List<Debt> = debts.getOpen().map { it.toDomain() }

    /** Creates a debt and records its opening balance. */
    suspend fun add(debt: Debt): Long {
        val db = databases.require()
        val now = Instant.now(clock)

        return db.withTransaction {
            val prepared = debt.copy(
                originalBalance = debt.originalBalance ?: debt.currentBalance,
                sortOrder = if (debt.sortOrder == 0) debts.nextSortOrder() else debt.sortOrder,
                createdAt = now,
                updatedAt = now
            )
            val id = debts.insert(prepared.toEntity().copy(id = 0))

            changes.insert(
                journalEntry(
                    subjectId = id,
                    subjectName = prepared.name,
                    field = FIELD_BALANCE,
                    previous = null,
                    next = prepared.currentBalance,
                    source = ChangeSource.INITIAL,
                    at = now
                )
            )
            id
        }
    }

    /**
     * Updates a debt, journalling whichever amounts actually changed. Also
     * closes the debt automatically when the balance reaches zero, and
     * reopens it if a balance comes back.
     */
    suspend fun update(debt: Debt, source: ChangeSource = ChangeSource.MANUAL, note: String? = null) {
        val db = databases.require()
        val now = Instant.now(clock)

        db.withTransaction {
            val existing = debts.getById(debt.id)?.toDomain()
                ?: throw IllegalArgumentException("No debt with id ${debt.id}")

            // Nothing closes itself.
            //
            // This used to close any debt whose balance was zero, which made a
            // card vanish the moment it was edited. Narrowing that to "only
            // when the balance falls to zero" was still wrong: a paid-off card
            // is often worth keeping on the list, and the app deciding to hide
            // a financial record is not its call to make. Removing an entry is
            // an explicit action with a Delete button behind it.
            val closedAt = existing.closedAt

            debts.update(
                debt.copy(
                    closedAt = closedAt,
                    createdAt = existing.createdAt,
                    updatedAt = now
                ).toEntity()
            )

            journalIfChanged(
                debt.id, debt.name, FIELD_BALANCE,
                existing.currentBalance, debt.currentBalance, source, note, now
            )
            journalIfChanged(
                debt.id, debt.name, FIELD_SCHEDULED_PAYMENT,
                existing.scheduledPayment, debt.scheduledPayment, source, note, now
            )
            journalIfChanged(
                debt.id, debt.name, FIELD_MINIMUM_PAYMENT,
                existing.minimumPayment, debt.minimumPayment, source, note, now
            )
        }
    }

    /**
     * Records a payment against a debt. Convenience over [update], and it
     * labels the journal entry as a payment rather than an edit, which is
     * what makes history readable later.
     */
    suspend fun recordPayment(debtId: Long, amount: Money, note: String? = null) {
        require(amount.isPositive) { "A payment must be a positive amount" }
        val current = getById(debtId) ?: throw IllegalArgumentException("No debt with id $debtId")

        val newBalance = (current.currentBalance - amount).let {
            if (it.isNegative) Money.ZERO else it
        }
        update(current.copy(currentBalance = newBalance), ChangeSource.PAYMENT, note)
    }

    suspend fun delete(id: Long) {
        // The journal goes too, matching how savings accounts behave.
        //
        // Keeping it meant the Overview's recent activity could show payments
        // against a debt that no longer exists anywhere else in the app, which
        // is a worse kind of confusing than losing the history: the figures
        // cannot be reconciled against anything. Delete should mean gone.
        val db = databases.require()
        db.withTransaction {
            db.balanceChangeDao().deleteFor(SubjectType.DEBT.name, id)
            db.debtDao().deleteById(id)
        }
    }

    fun observeHistory(debtId: Long): Flow<List<BalanceChange>> =
        fromDatabase { it.balanceChangeDao().observeFor(SubjectType.DEBT.name, debtId) }
            .map { list -> list.map { c -> c.toDomain() } }

    /** Recent activity across every debt, for the overview. */
    fun observeRecentActivity(limit: Int = 20): Flow<List<BalanceChange>> =
        fromDatabase { it.balanceChangeDao().observeRecent(limit) }
            .map { list -> list.map { c -> c.toDomain() } }

    private suspend fun journalIfChanged(
        subjectId: Long,
        subjectName: String,
        field: String,
        previous: Money,
        next: Money,
        source: ChangeSource,
        note: String?,
        at: Instant
    ) {
        if (previous == next) return
        changes.insert(
            journalEntry(subjectId, subjectName, field, previous, next, source, at, note)
        )
    }

    private fun journalEntry(
        subjectId: Long,
        subjectName: String,
        field: String,
        previous: Money?,
        next: Money,
        source: ChangeSource,
        at: Instant,
        note: String? = null
    ) = BalanceChangeEntity(
        subjectType = SubjectType.DEBT.name,
        subjectId = subjectId,
        field = field,
        previousMinor = previous?.minorUnits,
        newMinor = next.minorUnits,
        deltaMinor = next.minorUnits - (previous?.minorUnits ?: 0L),
        source = source.name,
        subjectName = subjectName,
        note = note,
        occurredAt = at.toEpochMilli()
    )

    companion object {
        const val FIELD_BALANCE = "balance"
        const val FIELD_SCHEDULED_PAYMENT = "scheduled_payment"
        const val FIELD_MINIMUM_PAYMENT = "minimum_payment"
    }
}
