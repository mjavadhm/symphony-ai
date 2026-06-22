package io.github.zyrouge.symphony.services.database.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.zyrouge.symphony.services.database.entities.PlaybackHistory

@Dao
interface PlaybackHistoryStore {
    @Insert
    suspend fun insert(history: PlaybackHistory)

    @Query("SELECT songId FROM playback_history GROUP BY songId ORDER BY COUNT(id) DESC LIMIT :limit")
    fun getMostPlayedSongs(limit: Int): kotlinx.coroutines.flow.Flow<List<String>>

    @Query("SELECT songId FROM playback_history ORDER BY playedAt DESC LIMIT :limit")
    fun getRecentlyPlayedSongs(limit: Int): kotlinx.coroutines.flow.Flow<List<String>>
    
    @Query("SELECT COUNT(*) FROM playback_history WHERE songId = :songId")
    suspend fun getPlayCount(songId: String): Int

    @Query("SELECT * FROM playback_history ORDER BY playedAt DESC")
    suspend fun getAllHistory(): List<PlaybackHistory>
}
