package com.example.nutritracker.data.repository

import com.example.nutritracker.data.dao.ChatMessageDao
import com.example.nutritracker.data.dao.ConversationDao
import com.example.nutritracker.data.entity.ChatMessage
import com.example.nutritracker.data.entity.ChatRole
import com.example.nutritracker.data.entity.Conversation
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: ChatMessageDao
) {
    // ── 会话 ──

    suspend fun ensureConversation(): Conversation {
        conversationDao.getLatest()?.let { return it }
        val conversation = Conversation(title = "新对话")
        val id = conversationDao.upsert(conversation)
        return conversation.copy(id = id)
    }

    suspend fun getById(id: Long): Conversation? = conversationDao.getById(id)

    fun getByIdFlow(id: Long): Flow<Conversation?> = conversationDao.getByIdFlow(id)

    suspend fun getLatest(): Conversation? = conversationDao.getLatest()

    suspend fun getRecent(limit: Int = 20): List<Conversation> = conversationDao.getRecent(limit)

    /** 备份导出用：全量会话，无上限 */
    suspend fun getAll(): List<Conversation> = conversationDao.getAll()

    suspend fun touch(conversation: Conversation) {
        conversationDao.update(conversation.copy(updatedAt = LocalDateTime.now()))
    }

    suspend fun rename(id: Long, title: String) =
        conversationDao.updateTitle(id, title, LocalDateTime.now())

    suspend fun updateTodo(id: Long, todoJson: String?) =
        conversationDao.updateTodo(id, todoJson, LocalDateTime.now())

    suspend fun updateSummary(id: Long, summary: String?) =
        conversationDao.updateSummary(id, summary, LocalDateTime.now())

    suspend fun updatePendingTool(id: Long, json: String?) =
        conversationDao.updatePendingTool(id, json, LocalDateTime.now())

    suspend fun deleteConversation(id: Long) = conversationDao.deleteById(id)

    suspend fun deleteAllConversations() {
        conversationDao.deleteAll()
        messageDao.deleteAll()
    }

    /** 备份恢复：直接建会话并返回新 id */
    suspend fun importConversation(
        title: String,
        summary: String?,
        todoJson: String?,
        pendingToolJson: String?,
        createdAt: LocalDateTime,
        updatedAt: LocalDateTime
    ): Long =
        conversationDao.upsert(
            Conversation(
                title = title,
                summary = summary,
                todoJson = todoJson,
                pendingToolJson = pendingToolJson,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        )

    suspend fun restoreTimestamps(id: Long, createdAt: LocalDateTime, updatedAt: LocalDateTime) {
        conversationDao.getById(id)?.let {
            conversationDao.update(it.copy(createdAt = createdAt, updatedAt = updatedAt))
        }
    }

    // ── 消息 ──

    suspend fun appendMessage(
        conversationId: Long,
        role: ChatRole,
        content: String,
        toolCallJson: String? = null,
        toolCallId: String? = null,
        toolName: String? = null,
        cardType: String? = null,
        payloadJson: String? = null,
        imagePath: String? = null
    ): ChatMessage {
        val message = ChatMessage(
            conversationId = conversationId,
            role = role,
            content = content,
            toolCallJson = toolCallJson,
            toolCallId = toolCallId,
            toolName = toolName,
            cardType = cardType,
            payloadJson = payloadJson,
            imagePath = imagePath
        )
        val id = messageDao.insert(message)
        conversationDao.getById(conversationId)?.let { touch(it) }
        return message.copy(id = id)
    }

    fun getMessagesFlow(conversationId: Long): Flow<List<ChatMessage>> =
        messageDao.getByConversationFlow(conversationId)

    suspend fun getMessages(conversationId: Long): List<ChatMessage> =
        messageDao.getByConversation(conversationId)

    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<ChatMessage> =
        messageDao.getRecent(conversationId, limit).reversed()
}
