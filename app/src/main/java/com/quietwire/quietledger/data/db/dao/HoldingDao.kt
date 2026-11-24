package com.quietwire.quietledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.quietwire.quietledger.data.db.entity.HoldingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HoldingDao {

    @Query("SELECT * FROM holding ORDER BY is_cash, current_value_minor DESC")
    fun observeAll(): Flow<List<HoldingEntity>>

    /** COALESCE because SUM over no rows is null rather than zero. */
    @Query("SELECT COALESCE(SUM(current_value_minor), 0) FROM holding")
    fun observeTotal(): Flow<Long>

    @Query("SELECT MAX(as_of) FROM holding")
    fun observeAsOf(): Flow<Long?>

    @Query("SELECT * FROM holding ORDER BY id")
    suspend fun getAll(): List<HoldingEntity>

    @Insert
    suspend fun insert(holding: HoldingEntity): Long

    @Insert
    suspend fun insertAll(holdings: List<HoldingEntity>)

    /**
     * Replaces every holding for one account.
     *
     * A positions export is a snapshot, so anything no longer in the file has
     * been sold. Merging would leave it behind, inflating net worth for ever.
     */
    @Query("DELETE FROM holding WHERE account_name = :accountName")
    suspend fun deleteForAccount(accountName: String)

    @Query("DELETE FROM holding")
    suspend fun deleteAll()
}
