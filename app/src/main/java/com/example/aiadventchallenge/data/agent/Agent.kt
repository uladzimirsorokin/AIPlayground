package com.example.aiadventchallenge.data.agent

import com.example.aiadventchallenge.data.ChatMessage

interface Agent {
    suspend fun send(userMessage: String): AgentResponse
    fun clearHistory()
    val history: List<ChatMessage>
}

data class AgentStats(
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val costUsd: Double = 0.0
)

data class AgentResponse(
    val reply: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
    val stats: AgentStats,
    val compacted: Boolean = false,
    val model: String? = null,
    val truncated: Boolean = false
)