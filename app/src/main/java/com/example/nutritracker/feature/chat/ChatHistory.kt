package com.example.nutritracker.feature.chat

import com.example.nutritracker.data.entity.ChatMessage
import com.example.nutritracker.data.entity.ChatRole

/** Excludes the just-persisted user message; AgentHarness appends userText itself. */
internal fun historyForTurn(
    messages: List<ChatMessage>,
    newlyAddedUserMessageId: Long?,
    hasPriorSummary: Boolean = false,
    recentUserTurns: Int = 16
): List<ChatMessage> {
    val history = if (newlyAddedUserMessageId == null) {
        messages
    } else {
        messages.filterNot { it.id == newlyAddedUserMessageId }
    }

    if (!hasPriorSummary || recentUserTurns <= 0) return history
    val userIndexes = history.indices.filter { history[it].role == ChatRole.USER }
    if (userIndexes.size <= recentUserTurns) return history

    // Start at a user turn boundary so assistant tool calls and their results stay together.
    return history.drop(userIndexes[userIndexes.size - recentUserTurns])
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
