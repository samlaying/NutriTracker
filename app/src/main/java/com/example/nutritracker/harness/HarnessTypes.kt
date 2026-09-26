package com.example.nutritracker.harness

/**
 * Harness 核心线格式类型（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 * 对齐 OpenAI 兼容 /chat/completions 的 function calling 协议。
 */

/** 一次模型调用配置（每轮从 DataStore 读取构建） */
data class HarnessConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val fallbackModel: String? = null,
    val summaryModel: String? = null,
    val temperature: Double = 0.4,
    val maxTokens: Int = 4096
)

/** OpenAI 工具调用（assistant 消息携带） */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** 统一消息线格式：system / user / assistant / tool */
data class HarnessMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null
) {
    companion object {
        fun system(text: String) = HarnessMessage(role = "system", content = text)
        fun user(text: String) = HarnessMessage(role = "user", content = text)
        fun tool(callId: String, toolName: String, text: String) =
            HarnessMessage(role = "tool", content = text, toolCallId = callId, name = toolName)
    }
}

/** 注入 system prompt 的工具描述（JSON Schema 参数） */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)

/** 中间件限次参数（教程 50/200 的移动端缩放） */
data class HarnessLimits(
    val maxModelCalls: Int = 12,
    val maxToolCalls: Int = 40,
    /** 上下文估算超过该字符数触发保底压缩（≈85% 窗口） */
    val contextCompressThresholdChars: Int = 90_000,
    /** 单条工具输出超过该字符数即卸载：完整结果只进 UI payload，模型只收摘要 */
    val toolOutputOffloadChars: Int = 20_000
)

/** 模型流式返回事件 */
sealed class ModelStreamEvent {
    data class ContentDelta(val delta: String) : ModelStreamEvent()
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsDelta: String?
    ) : ModelStreamEvent()

    data class Completed(val finishReason: String?) : ModelStreamEvent()
    data class Failed(val error: Throwable) : ModelStreamEvent()
}

/** 估算文本 token（中文占比高的粗略近似） */
fun estimateTokens(text: String): Int = (text.length * 0.6).toInt().coerceAtLeast(1)
