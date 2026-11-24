package com.quietwire.quietledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.quietwire.quietledger.data.db.entity.RecurringExpenseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringExpenseDao {

    @Query("SELECT * FROM recurring_expense ORDER BY sort_order, name")
    fun observeAll(): Flow<List<RecurringExpenseEntity>>

    @Query("SELECT * FROM recurring_expense WHERE is_active = 1 ORDER BY sort_order, name")
    fun observeActive(): Flow<List<RecurringExpenseEntity>>

    @Query("SELECT * FROM recurring_expense WHERE is_active = 1")
    suspend fun getActive(): List<RecurringExpenseEntity>

    @Query("SELECT * FROM recurring_expense WHERE id = :id")
    suspend fun getById(id: Long): RecurringExpenseEntity?

    @Insert
    suspend fun insert(expense: RecurringExpenseEntity): Long

    @Update
    suspend fun update(expense: RecurringExpenseEntity)

    @Query("DELETE FROM recurring_expense WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM recurring_expense")
    suspend fun nextSortOrder(): Int

    @Query("SELECT * FROM recurring_expense ORDER BY id")
    suspend fun getAllIncludingInactive(): List<RecurringExpenseEntity>

    @Query("DELETE FROM recurring_expense")
    suspend fun deleteAll()
}
