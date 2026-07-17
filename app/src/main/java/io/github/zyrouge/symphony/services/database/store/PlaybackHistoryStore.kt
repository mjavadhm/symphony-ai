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

    @Query("SELECT * FROM playback_history WHERE playedAt >= :since ORDER BY playedAt DESC")
    suspend fun getHistorySince(since: Long): List<PlaybackHistory>

    @Query("SELECT songId FROM playback_history WHERE hourOfDay BETWEEN :startHour AND :endHour AND skipped = 0 GROUP BY songId ORDER BY COUNT(id) DESC LIMIT :limit")
    suspend fun getTopSongsForHours(startHour: Int, endHour: Int, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM playback_history WHERE skipped = 1 AND songId = :songId")
    suspend fun getSkipCount(songId: String): Int

    @Query("SELECT DISTINCT songId FROM playback_history")
    suspend fun getAllPlayedSongIds(): List<String>

    @Query("SELECT DISTINCT songId FROM playback_history WHERE skipped = 1 AND playedAt >= :since")
    suspend fun getRecentlySkippedSongIds(since: Long): List<String>
}
