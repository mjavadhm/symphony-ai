package io.github.zyrouge.symphony.services.database.store

import androidx.room.*
import io.github.zyrouge.symphony.services.database.entities.ChatMessage
import io.github.zyrouge.symphony.services.database.entities.ChatSession

@Dao
interface ChatStore {
    @Insert
    suspend fun insertSession(session: ChatSession): Long

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: Long, updatedAt: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: Long)

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getSessions(): List<ChatSession>

    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getMessages(sessionId: Long): List<ChatMessage>
}
