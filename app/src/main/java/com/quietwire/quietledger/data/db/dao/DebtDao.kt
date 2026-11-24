package com.quietwire.quietledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.quietwire.quietledger.data.db.entity.DebtEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtDao {

    @Query("SELECT * FROM debt ORDER BY sort_order, name")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debt WHERE closed_at IS NULL ORDER BY sort_order, name")
    fun observeOpen(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debt WHERE closed_at IS NULL ORDER BY sort_order, name")
    suspend fun getOpen(): List<DebtEntity>

    @Query("SELECT * FROM debt WHERE id = :id")
    suspend fun getById(id: Long): DebtEntity?

    @Query("SELECT * FROM debt WHERE id = :id")
    fun observeById(id: Long): Flow<DebtEntity?>

    @Query("SELECT COALESCE(SUM(current_balance_minor), 0) FROM debt WHERE closed_at IS NULL")
    fun observeTotalOutstanding(): Flow<Long>

    @Insert
    suspend fun insert(debt: DebtEntity): Long

    @Update
    suspend fun update(debt: DebtEntity)

    @Query("DELETE FROM debt WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM debt")
    suspend fun nextSortOrder(): Int

    @Query("SELECT * FROM debt ORDER BY id")
    suspend fun getAll(): List<DebtEntity>

    @Query("DELETE FROM debt")
    suspend fun deleteAll()
}
