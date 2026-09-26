package com.example.nutritracker.harness

/**
 * Agent 对 UI 发出的类型化事件流（教程 SSE 事件流的 App 内等价物：
 * AI 文本 / 工具消息 / 中断 / 任务清单 分类渲染）。
 */
sealed class AgentEvent {
    /** 模型正文流式 token */
    data class Token(val delta: String) : AgentEvent()

    /** 本轮 assistant 消息流式阶段结束（可能带工具调用；UI 侧负责持久化） */
    data class AssistantTurnCompleted(
        val content: String,
        val toolCalls: List<ToolCall>
    ) : AgentEvent()

    data class ToolStarted(
        val callId: String,
        val toolName: String,
        val argsSummary: String
    ) : AgentEvent()

    data class ToolFinished(
        val callId: String,
        val toolName: String,
        val ok: Boolean,
        val summary: String,
        val cardType: String? = null,
        val payloadJson: String? = null
    ) : AgentEvent()

    /** TodoList 更新（独立于消息流的 state key） */
    data class TodoUpdated(val items: List<TodoItem>) : AgentEvent()

    /** 人工介入挂起：缺数据表单 / 高危写确认。挂起期间 UI 锁定输入 */
    data class Interrupt(val pending: PendingInteraction) : AgentEvent()

    data class TurnCompleted(val reply: String?) : AgentEvent()
    data class TurnFailed(val message: String) : AgentEvent()
}

// ── 任务规划（Planning） ─────────────────────────────────────────────────────

data class TodoItem(
    val id: String,
    val content: String,
    // pending / in_progress / complete
    val status: String
) {
    companion object {
        fun fromJsonList(json: String?): List<TodoItem> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                com.google.gson.Gson().fromJson(json, Array<TodoItem>::class.java).toList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toJsonList(items: List<TodoItem>): String =
            com.google.gson.Gson().toJson(items)
    }
}

// ── 人工介入（HITL） ─────────────────────────────────────────────────────────

/**
 * 挂起的工具调用：中断恢复时的回放凭据。
 * resume 时按 resolution 合并参数重放或标记拒绝。
 */
data class PendingToolCall(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val interaction: PendingInteraction
)

sealed class PendingInteraction {
    /**
     * 缺数据表单式补充：已收集信息 + 缺失字段 + 建议值，让用户做选择题。
     * mergeKey 对应工具参数里要回填的字段名。
     */
    data class MissingData(
        val question: String,
        val collectedSummary: String,
        val fields: List<MissingField>
    ) : PendingInteraction()

    /** 高危写操作同意/拒绝式确认 */
    data class ConfirmWrite(
        val title: String,
        val detail: String
    ) : PendingInteraction()
}

data class MissingField(
    val key: String,
    val label: String,
    val suggestions: List<String> = emptyList()
)

/** 用户对挂起中断的回答 */
sealed class ToolResolution {
    /** 表单补充完成：各字段值已并入原参数 */
    data class FormFilled(val values: Map<String, String>) : ToolResolution()

    /** 确认执行原参数 */
    object Approved : ToolResolution()

    /** 拒绝执行 */
    object Rejected : ToolResolution()

    /** 用户自由文本回答（非选择题场景） */
    data class FreeText(val text: String) : ToolResolution()
}

/** 工具结果富卡片（UI 按 type 渲染） */
data class ChatCard(val type: String, val payloadJson: String)

/** 工具执行结果：summary 回传模型（回源验证后的真实结果），card 给 UI，interrupt 请求 HITL */
data class ToolResult(
    val summary: String,
    val cardType: String? = null,
    val payloadJson: String? = null,
    val interrupt: PendingInteraction? = null,
    val success: Boolean = true
) {
    companion object {
        fun ok(summary: String, cardType: String? = null, payloadJson: String? = null) =
            ToolResult(summary, cardType, payloadJson)

        fun failure(summary: String) = ToolResult(summary = summary, success = false)
    }
}
