package io.github.zyrouge.symphony.services.database.store

import androidx.room.*
import io.github.zyrouge.symphony.services.database.entities.CustomMix
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomMixStore {
    @Insert
    suspend fun insert(mix: CustomMix): Long

    @Update
    suspend fun update(mix: CustomMix)

    @Delete
    suspend fun delete(mix: CustomMix)

    @Query("SELECT * FROM custom_mixes ORDER BY sortOrder ASC, id ASC")
    fun getAll(): Flow<List<CustomMix>>

    @Query("SELECT COUNT(*) FROM custom_mixes")
    suspend fun count(): Int
}
