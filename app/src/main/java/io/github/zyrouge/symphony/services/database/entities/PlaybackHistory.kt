package io.github.zyrouge.symphony.services.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_history")
data class PlaybackHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val playedAt: Long,
    val durationPlayed: Long,
    val isShuffleMode: Boolean = false,
    val loopMode: String = "None",
    // v2 — for the recommender
    val songDurationMs: Long = 0,
    val completionRate: Float = 0f,      // what fraction of the song was played
    val skipped: Boolean = false,        // negative signal
    val hourOfDay: Int = -1,             // 0..23
    val dayOfWeek: Int = -1,             // Calendar.DAY_OF_WEEK
    // Metadata for merging across devices (songId is device-specific)
    val title: String = "",
    val artist: String = "",
    // Device
    val deviceId: String = "",
    val deviceName: String = "",
    // v3 — playback source and audio output
    val source: String = "",       // queue / daily_mix / mood_mix / discover_prompt / discover_similar
    val audioOutput: String = "",  // speaker / wired / bluetooth
)
