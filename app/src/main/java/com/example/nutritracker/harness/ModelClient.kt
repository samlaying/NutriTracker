package com.example.nutritracker.harness

import kotlinx.coroutines.flow.Flow

/**
 * 模型客户端抽象。生产实现走 OpenAI 兼容 /chat/completions；
 * JVM 单测注入 FakeModelClient 驱动 Harness 循环。
 */
interface ModelClient {
    /** 流式对话（主 Agent 轮次，token 级事件） */
    fun streamChat(
        config: HarnessConfig,
        messages: List<HarnessMessage>,
        tools: List<ToolDefinition>
    ): Flow<ModelStreamEvent>

    /** 非流式对话（子 Agent / 摘要 / 记忆回写），返回完整 assistant 消息（可带工具调用） */
    suspend fun chat(
        config: HarnessConfig,
        messages: List<HarnessMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Double = 0.4,
        maxTokens: Int = 2048
    ): HarnessMessage
}
