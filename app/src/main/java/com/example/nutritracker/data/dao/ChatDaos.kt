package com.example.nutritracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.nutritracker.data.entity.AgentTask
import com.example.nutritracker.data.entity.AgentTaskStatus
import com.example.nutritracker.data.entity.ChatMessage
import com.example.nutritracker.data.entity.ChatRole
import com.example.nutritracker.data.entity.Conversation
import com.example.nutritracker.data.entity.UserMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): Conversation?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Conversation?>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(): Conversation?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Conversation>

    @Query("UPDATE conversations SET todoJson = :todoJson, updatedAt = :now WHERE id = :id")
    suspend fun updateTodo(id: Long, todoJson: String?, now: java.time.LocalDateTime)

    @Query("UPDATE conversations SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String?, now: java.time.LocalDateTime)

    @Query("UPDATE conversations SET pendingToolJson = :json, updatedAt = :now WHERE id = :id")
    suspend fun updatePendingTool(id: Long, json: String?, now: java.time.LocalDateTime)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, now: java.time.LocalDateTime)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun getByConversationFlow(conversationId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getByConversation(conversationId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(conversationId: Long, limit: Int): List<ChatMessage>

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: Long)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

@Dao
interface UserMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: UserMemory): Long

    @Query("SELECT * FROM user_memory ORDER BY updatedAt ASC")
    suspend fun getAll(): List<UserMemory>

    @Query("SELECT * FROM user_memory ORDER BY updatedAt ASC")
    fun getAllFlow(): Flow<List<UserMemory>>

    @Query("SELECT * FROM user_memory WHERE category = :category ORDER BY updatedAt ASC")
    suspend fun getByCategory(category: String): List<UserMemory>

    @Query("DELETE FROM user_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM user_memory WHERE content LIKE '%' || :content || '%' AND category = :category")
    suspend fun deleteByContentLike(category: String, content: String)

    @Query("DELETE FROM user_memory")
    suspend fun deleteAll()
}

@Dao
interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: AgentTask): Long

    @Update
    suspend fun update(task: AgentTask)

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun getById(id: Long): AgentTask?

    @Query("SELECT * FROM agent_tasks WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    suspend fun getByConversation(conversationId: Long): List<AgentTask>

    @Query("SELECT * FROM agent_tasks WHERE status IN (:statuses) ORDER BY createdAt DESC")
    suspend fun getByStatuses(statuses: List<AgentTaskStatus>): List<AgentTask>

    @Query("UPDATE agent_tasks SET status = :status, result = :result, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, status: AgentTaskStatus, result: String?, finishedAt: java.time.LocalDateTime)

    @Query("DELETE FROM agent_tasks")
    suspend fun deleteAll()
}
