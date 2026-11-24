package com.quietwire.quietledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.quietwire.quietledger.data.db.entity.BalanceChangeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Append only. There is deliberately no update or delete, because an audit
 * trail you can edit is not an audit trail.
 */
@Dao
interface BalanceChangeDao {

    @Insert
    suspend fun insert(change: BalanceChangeEntity): Long

    @Query(
        "SELECT * FROM balance_change " +
            "WHERE subject_type = :subjectType AND subject_id = :subjectId " +
            "ORDER BY occurred_at DESC"
    )
    fun observeFor(subjectType: String, subjectId: Long): Flow<List<BalanceChangeEntity>>

    @Query("SELECT * FROM balance_change ORDER BY occurred_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<BalanceChangeEntity>>

    @Query(
        "SELECT * FROM balance_change " +
            "WHERE occurred_at >= :fromInclusive AND occurred_at < :toExclusive " +
            "ORDER BY occurred_at"
    )
    suspend fun between(fromInclusive: Long, toExclusive: Long): List<BalanceChangeEntity>

    /**
     * Net movement for a subject over a window, used to chart progress
     * without walking the whole table.
     */
    @Query(
        "SELECT COALESCE(SUM(delta_minor), 0) FROM balance_change " +
            "WHERE subject_type = :subjectType AND subject_id = :subjectId " +
            "AND occurred_at >= :fromInclusive"
    )
    suspend fun netChangeSince(subjectType: String, subjectId: Long, fromInclusive: Long): Long

    /**
     * Only for removing a record entered by mistake. The journal is otherwise
     * append only, because rewriting history is how balances stop adding up.
     */
    @Query("DELETE FROM balance_change WHERE subject_type = :subjectType AND subject_id = :subjectId")
    suspend fun deleteFor(subjectType: String, subjectId: Long)

    @Query("SELECT * FROM balance_change ORDER BY id")
    suspend fun getAll(): List<BalanceChangeEntity>

    /**
     * Only used when replacing everything during an import, which happens in
     * the same transaction. The journal is otherwise append only.
     */
    @Query("DELETE FROM balance_change")
    suspend fun deleteAll()
}
