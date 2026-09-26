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
