package com.example.nutritracker.feature.chat

import com.example.nutritracker.data.entity.ChatMessage

/** Excludes the just-persisted user message; AgentHarness appends userText itself. */
internal fun historyForTurn(
    messages: List<ChatMessage>,
    newlyAddedUserMessageId: Long?
): List<ChatMessage> = if (newlyAddedUserMessageId == null) {
    messages
} else {
    messages.filterNot { it.id == newlyAddedUserMessageId }
}

/**
 * Wire 层去重：同一 toolCallId 只保留最后一条 TOOL 结果。
 * HITL 挂起时先落一条「等待用户确认」占位，恢复轮执行后又落一条真实结果，
 * 数据库里会短暂存在同 id 两条记录；OpenAI 协议要求每个 tool_call 恰好一条响应，
 * 发送前在此收敛（保留 id 最大的一条，即最新结果）。
 */
internal fun latestToolResultsOnly(messages: List<ChatMessage>): List<ChatMessage> {
    val lastIdByCallId = HashMap<String, Long>()
    messages.forEach { message ->
        val callId = message.toolCallId ?: return@forEach
        lastIdByCallId[callId] = message.id
    }
    return messages.filter { message ->
        message.toolCallId == null || lastIdByCallId[message.toolCallId] == message.id
    }
}
