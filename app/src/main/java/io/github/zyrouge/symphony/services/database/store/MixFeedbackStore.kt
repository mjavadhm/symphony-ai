package io.github.zyrouge.symphony.services.database.store

import androidx.room.*
import io.github.zyrouge.symphony.services.database.entities.MixFeedback

@Dao
interface MixFeedbackStore {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feedback: MixFeedback)

    @Query("SELECT * FROM mix_feedback")
    suspend fun getAll(): List<MixFeedback>

    @Query("DELETE FROM mix_feedback WHERE songId = :songId")
    suspend fun remove(songId: String)
}
