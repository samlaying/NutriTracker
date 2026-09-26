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
}
