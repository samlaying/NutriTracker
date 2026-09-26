package com.example.nutritracker.data.entity

import androidx.room.*
import java.time.LocalDateTime

// ── 对话 ─────────────────────────────────────────────────────────────────────

enum class ChatRole { USER, ASSISTANT, TOOL }

/**
 * 对话会话。todoJson 是 Harness 的任务清单独立存储位：
 * 永不进入 messages、永不参与摘要压缩（教程 Planning 的"遗忘"大坑防线）。
 * summary 是上下文压缩后的历史摘要产物。
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新对话",
    val summary: String? = null,
    val todoJson: String? = null,
    val pendingToolJson: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: ChatRole,
    val content: String,
    // ASSISTANT 消息携带的 function calling 调用（Gson 序列化）
    val toolCallJson: String? = null,
    // TOOL 消息对应的 call id / 工具名
    val toolCallId: String? = null,
    val toolName: String? = null,
    // UI 富卡片类型：meal_logged / water_logged / weight_logged / activity_logged /
    // plan_created / plan_updated / sources_cited / screen_link / task_started / task_done / photo_analysis
    val cardType: String? = null,
    // 卡片数据或被卸载（offload）的完整工具输出
    val payloadJson: String? = null,
    val imagePath: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

// ── 长期记忆（用户偏好，每轮注入 + 对话后自动回写） ────────────────────────────

@Entity(tableName = "user_memory")
data class UserMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // 偏好 / 伤病史 / 器械条件 / 口味 / 作息 / 目标背景
    val category: String,
    val content: String,
    val source: String = "agent",
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

// ── 异步子 Agent 任务 ────────────────────────────────────────────────────────

enum class AgentTaskStatus { PENDING, RUNNING, DONE, FAILED, CANCELLED }

@Entity(tableName = "agent_tasks")
data class AgentTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long? = null,
    val kind: String,
    val title: String,
    // 委派 5 要素打包后的完整任务说明
    val prompt: String,
    val status: AgentTaskStatus = AgentTaskStatus.PENDING,
    val result: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val finishedAt: LocalDateTime? = null
)
