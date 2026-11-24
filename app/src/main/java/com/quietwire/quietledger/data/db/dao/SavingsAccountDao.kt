package com.quietwire.quietledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.quietwire.quietledger.data.db.entity.SavingsAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavingsAccountDao {

    @Query("SELECT * FROM savings_account WHERE closed_at IS NULL ORDER BY sort_order, name")
    fun observeOpen(): Flow<List<SavingsAccountEntity>>

    @Query("SELECT * FROM savings_account ORDER BY sort_order, name")
    fun observeAll(): Flow<List<SavingsAccountEntity>>

    /**
     * Total of open accounts, in minor units.
     *
     * COALESCE because SUM over no rows is null, and a null total silently
     * becomes zero in some paths and an exception in others.
     */
    @Query("SELECT COALESCE(SUM(current_balance_minor), 0) FROM savings_account WHERE closed_at IS NULL")
    fun observeTotal(): Flow<Long>

    @Query("SELECT * FROM savings_account WHERE closed_at IS NULL")
    suspend fun getOpen(): List<SavingsAccountEntity>

    @Query("SELECT * FROM savings_account ORDER BY id")
    suspend fun getAll(): List<SavingsAccountEntity>

    @Query("SELECT * FROM savings_account WHERE id = :id")
    suspend fun getById(id: Long): SavingsAccountEntity?

    @Insert
    suspend fun insert(account: SavingsAccountEntity): Long

    @Update
    suspend fun update(account: SavingsAccountEntity)

    @Query("DELETE FROM savings_account WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM savings_account")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM savings_account")
    suspend fun nextSortOrder(): Int
}
