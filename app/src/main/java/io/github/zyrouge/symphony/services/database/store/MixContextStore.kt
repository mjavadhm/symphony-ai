package io.github.zyrouge.symphony.services.database.store

import androidx.room.*
import io.github.zyrouge.symphony.services.database.entities.MixContext
import kotlinx.coroutines.flow.Flow

@Dao
interface MixContextStore {
    @Insert
    suspend fun insert(context: MixContext): Long

    @Update
    suspend fun update(context: MixContext)

    @Delete
    suspend fun delete(context: MixContext)

    @Query("SELECT * FROM mix_contexts ORDER BY startHour")
    fun getAll(): Flow<List<MixContext>>

    @Query("SELECT COUNT(*) FROM mix_contexts")
    suspend fun count(): Int
}
