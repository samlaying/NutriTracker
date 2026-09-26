package com.example.nutritracker.data.repository

import com.example.nutritracker.data.dao.UserMemoryDao
import com.example.nutritracker.data.entity.UserMemory
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 长期记忆（用户偏好/伤病/器械/口味/作息）。
 * 教程 MemoryUpdate 机制：每轮注入上下文，对话后由中间件自动回写。
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: UserMemoryDao
) {
    suspend fun getAll(): List<UserMemory> = dao.getAll()

    fun getAllFlow(): Flow<List<UserMemory>> = dao.getAllFlow()

    suspend fun getByCategory(category: String): List<UserMemory> = dao.getByCategory(category)

    suspend fun upsert(memory: UserMemory): Long = dao.upsert(memory)

    suspend fun add(category: String, content: String, source: String = "agent"): Long =
        dao.upsert(UserMemory(category = category, content = content, source = source))

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteByContentLike(category: String, content: String) =
        dao.deleteByContentLike(category, content)

    suspend fun deleteAll() = dao.deleteAll()

    /** 拼成注入上下文的紧凑文本块 */
    suspend fun formatForContext(): String {
        val memories = dao.getAll()
        if (memories.isEmpty()) return ""
        return memories.joinToString("\n") { "- [${it.category}] ${it.content}" }
    }
}
