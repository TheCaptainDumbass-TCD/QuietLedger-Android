package com.quietwire.quietledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.quietwire.quietledger.data.db.entity.MetalLotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MetalLotDao {

    @Query("SELECT * FROM metal_lot ORDER BY purchased_at DESC, id")
    fun observeAll(): Flow<List<MetalLotEntity>>

    /**
     * Total cost including allocated shipping, which is what was actually paid.
     * COALESCE because SUM over no rows is null rather than zero.
     */
    @Query("SELECT COALESCE(SUM(extended_price_minor + shipping_minor), 0) FROM metal_lot")
    fun observeTotalCost(): Flow<Long>

    @Query("SELECT * FROM metal_lot ORDER BY id")
    suspend fun getAll(): List<MetalLotEntity>

    @Insert
    suspend fun insert(lot: MetalLotEntity): Long

    @Insert
    suspend fun insertAll(lots: List<MetalLotEntity>)

    @Query("DELETE FROM metal_lot")
    suspend fun deleteAll()
}
