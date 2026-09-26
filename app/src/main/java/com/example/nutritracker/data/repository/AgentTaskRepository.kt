package com.example.nutritracker.data.repository

import com.example.nutritracker.data.dao.AgentTaskDao
import com.example.nutritracker.data.entity.AgentTask
import com.example.nutritracker.data.entity.AgentTaskStatus
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** 异步子 Agent 任务（周报/长分析）的持久化与查询 */
@Singleton
class AgentTaskRepository @Inject constructor(
    private val dao: AgentTaskDao
) {
    suspend fun create(conversationId: Long?, kind: String, title: String, prompt: String): AgentTask {
        val task = AgentTask(
            conversationId = conversationId,
            kind = kind,
            title = title,
            prompt = prompt,
            status = AgentTaskStatus.PENDING
        )
        return task.copy(id = dao.insert(task))
    }

    suspend fun update(task: AgentTask) = dao.update(task)

    suspend fun markRunning(id: Long) {
        dao.getById(id)?.let { dao.update(it.copy(status = AgentTaskStatus.RUNNING)) }
    }

    suspend fun finish(id: Long, status: AgentTaskStatus, result: String?) {
        dao.finish(id, status, result, LocalDateTime.now())
    }

    suspend fun getById(id: Long): AgentTask? = dao.getById(id)

    suspend fun getByConversation(conversationId: Long): List<AgentTask> =
        dao.getByConversation(conversationId)

    suspend fun getActive(): List<AgentTask> =
        dao.getByStatuses(listOf(AgentTaskStatus.PENDING, AgentTaskStatus.RUNNING))
}
