package io.github.zyrouge.symphony.services.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mix_feedback")
data class MixFeedback(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val liked: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)
