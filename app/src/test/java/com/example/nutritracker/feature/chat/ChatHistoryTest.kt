package com.example.nutritracker.feature.chat

import com.example.nutritracker.data.entity.ChatMessage
import com.example.nutritracker.data.entity.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryTest {
    @Test
    fun excludesJustPersistedUserMessageBecauseHarnessAddsItFromUserText() {
        val older = ChatMessage(id = 1, conversationId = 7, role = ChatRole.USER, content = "older")
        val justPersisted = ChatMessage(id = 2, conversationId = 7, role = ChatRole.USER, content = "current")

        val history = historyForTurn(listOf(older, justPersisted), newlyAddedUserMessageId = 2)

        assertEquals(listOf(older), history)
    }

    @Test
    fun keepsFullHistoryWhenThereIsNoNewUserMessage() {
        val existing = ChatMessage(id = 1, conversationId = 7, role = ChatRole.ASSISTANT, content = "reply")

        assertEquals(listOf(existing), historyForTurn(listOf(existing), newlyAddedUserMessageId = null))
    }

    @Test
    fun keepsOnlyLatestToolResultPerCallId() {
        val placeholder = ChatMessage(
            id = 2, conversationId = 7, role = ChatRole.TOOL, content = "等待用户确认",
            toolCallId = "call_a", toolName = "create_training_plan"
        )
        val realResult = ChatMessage(
            id = 5, conversationId = 7, role = ChatRole.TOOL, content = "计划已创建",
            toolCallId = "call_a", toolName = "create_training_plan"
        )
        val otherTool = ChatMessage(
            id = 6, conversationId = 7, role = ChatRole.TOOL, content = "ok",
            toolCallId = "call_b", toolName = "log_water"
        )
        val assistant = ChatMessage(id = 1, conversationId = 7, role = ChatRole.ASSISTANT, content = "好的")
        val user = ChatMessage(id = 7, conversationId = 7, role = ChatRole.USER, content = "继续")

        val wire = latestToolResultsOnly(listOf(assistant, placeholder, realResult, otherTool, user))

        assertEquals(listOf(assistant, realResult, otherTool, user), wire)
    }

    @Test
    fun keepsNonToolMessagesAndToolRowsWithoutCallIdUntouched() {
        val assistant = ChatMessage(id = 1, conversationId = 7, role = ChatRole.ASSISTANT, content = "hi")
        val orphanTool = ChatMessage(id = 2, conversationId = 7, role = ChatRole.TOOL, content = "（用户已撤销）", toolName = "undo")

        assertEquals(listOf(assistant, orphanTool), latestToolResultsOnly(listOf(assistant, orphanTool)))
    }

    @Test
    fun removesPendingPlaceholderWhenResumingTheSameCall() {
        val assistant = ChatMessage(
            id = 1, conversationId = 7, role = ChatRole.ASSISTANT, content = "",
            toolCallJson = "[{\"id\":\"call_a\",\"name\":\"create_training_plan\"}]"
        )
        val placeholder = ChatMessage(
            id = 2, conversationId = 7, role = ChatRole.TOOL, content = "等待用户确认",
            toolCallId = "call_a", toolName = "create_training_plan"
        )

        assertEquals(listOf(assistant), historyWithoutToolResultFor(listOf(assistant, placeholder), "call_a"))
    }
}
