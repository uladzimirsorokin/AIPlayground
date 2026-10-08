package com.example.aiadventchallenge.data

/** Провайдер LLM: облако (OpenAI-совместимый, напр. OpenRouter) или локальная модель (Ollama/LM Studio). */
enum class LlmProvider { CLOUD, LOCAL }

/**
 * Клиент, который на каждый вызов выбирает облачный или локальный [LlmClient] в зависимости
 * от текущего [provider]. Локальный клиент создаётся на вызов, поэтому смена эндпоинта/модели
 * в настройках применяется сразу. Наследует [LlmClient], чтобы остальной код агента
 * (память, состояние задачи и т.п.) не менялся.
 */
class RoutingLlmClient(
    private val provider: () -> LlmProvider,
    private val cloud: LlmClient,
    private val localEndpoint: () -> String,
    private val localModel: () -> String,
    private val robustLocal: () -> Boolean
) : LlmClient() {

    private fun active(): LlmClient =
        if (provider() == LlmProvider.LOCAL)
            LlmClient(endpoint = localEndpoint(), model = localModel(), antiRepeat = robustLocal())
        else cloud

    override suspend fun complete(
        prompt: String,
        apiKey: String,
        systemPrompt: String?,
        maxTokens: Int?,
        stop: List<String>?,
        responseFormat: String?,
        formatInstruction: String?,
        temperature: Double?
    ): String = active().complete(
        prompt, apiKey, systemPrompt, maxTokens, stop, responseFormat, formatInstruction, temperature
    )

    override suspend fun completeDetailed(
        prompt: String,
        apiKey: String,
        model: String,
        systemPrompt: String?
    ): CompletionResult = active().completeDetailed(prompt, apiKey, model, systemPrompt)

    override suspend fun completeChat(
        messages: List<ChatMessage>,
        apiKey: String,
        model: String,
        maxTokens: Int?,
        stop: List<String>?,
        responseFormat: String?,
        temperature: Double?,
        tools: List<ChatTool>?,
        reasoningEffort: String?
    ): CompletionResult = active().completeChat(
        messages, apiKey, model, maxTokens, stop, responseFormat, temperature, tools, reasoningEffort
    )
}
