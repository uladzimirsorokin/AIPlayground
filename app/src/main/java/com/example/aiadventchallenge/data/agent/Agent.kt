package com.example.aiadventchallenge.data.agent

import com.example.aiadventchallenge.data.ChatMessage

interface Agent {
    suspend fun send(userMessage: String): AgentResponse
    fun clearHistory()
    val history: List<ChatMessage>
}

data class AgentResponse(
    val reply: String,
    val tokensUsed: Int
)