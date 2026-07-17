package io.github.zyrouge.symphony.services.database.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.zyrouge.symphony.services.database.entities.TrackFlow

@Dao
interface TrackFlowStore {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trackFlow: TrackFlow)

    @Query("SELECT * FROM track_flow")
    suspend fun getAll(): List<TrackFlow>

    @Query("SELECT songId FROM track_flow")
    suspend fun getAnalyzedSongIds(): List<String>

    @Query("SELECT COUNT(*) FROM track_flow")
    suspend fun count(): Int
}
