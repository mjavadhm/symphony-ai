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
    // v2 — برای recommender
    val songDurationMs: Long = 0,
    val completionRate: Float = 0f,      // چند درصد آهنگ شنیده شد
    val skipped: Boolean = false,        // سیگنال منفی
    val hourOfDay: Int = -1,             // 0..23
    val dayOfWeek: Int = -1,             // Calendar.DAY_OF_WEEK
    // متادیتا برای merge بین دستگاه‌ها (songId دستگاه-محوره)
    val title: String = "",
    val artist: String = "",
    // دیوایس
    val deviceId: String = "",
    val deviceName: String = "",
    // v3 — منبع پخش و خروجی صدا
    val source: String = "",       // queue / daily_mix / mood_mix / discover_prompt / discover_similar
    val audioOutput: String = "",  // speaker / wired / bluetooth
)
