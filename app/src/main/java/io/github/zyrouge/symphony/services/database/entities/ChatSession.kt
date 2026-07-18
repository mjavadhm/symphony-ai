package io.github.zyrouge.symphony.services.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    // "user" | "bot" | "results"
    val kind: String,
    val text: String,
    // پرامپتهای CLAP، خطبهخط (فقط برای kind=results)
    @ColumnInfo(defaultValue = "") val prompts: String = "",
    // songId ها، خطبهخط (فقط برای kind=results)
    @ColumnInfo(defaultValue = "") val songIds: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
