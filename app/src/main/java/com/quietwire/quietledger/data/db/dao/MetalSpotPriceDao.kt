package com.quietwire.quietledger.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.quietwire.quietledger.data.db.entity.MetalSpotPriceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MetalSpotPriceDao {

    @Query("SELECT * FROM metal_spot_price")
    fun observeAll(): Flow<List<MetalSpotPriceEntity>>

    @Query("SELECT * FROM metal_spot_price")
    suspend fun getAll(): List<MetalSpotPriceEntity>

    /** Upsert, because re-entering a price replaces it rather than adding one. */
    @Upsert
    suspend fun upsert(price: MetalSpotPriceEntity)

    @Query("DELETE FROM metal_spot_price WHERE metal = :metal")
    suspend fun delete(metal: String)

    @Query("DELETE FROM metal_spot_price")
    suspend fun deleteAll()
}
