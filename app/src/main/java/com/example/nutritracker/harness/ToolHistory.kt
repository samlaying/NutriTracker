package com.example.nutritracker.harness

import com.example.nutritracker.data.entity.ChatMessage
import com.example.nutritracker.data.entity.ChatRole

/** Remove a persisted placeholder before a resumed tool outcome is injected into the wire history. */
internal fun historyWithoutToolResultFor(messages: List<ChatMessage>, callId: String): List<ChatMessage> =
    messages.filterNot { it.role == ChatRole.TOOL && it.toolCallId == callId }
