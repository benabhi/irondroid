package com.irondroid.ai

import kotlinx.coroutines.flow.Flow

/**
 * What a provider streams back for one request (RF-NAR-02): text as it arrives, each tool call once
 * it is complete, and a single [Finished] at the end.
 */
sealed interface ChatEvent {
    /** A piece of the answer's text, to show as soon as it arrives. */
    data class TextDelta(val text: String) : ChatEvent

    /** A piece of the model's reasoning, for models that show it; it is not shown to the player. */
    data class ReasoningDelta(val text: String) : ChatEvent

    /** A complete tool call: its arguments are all there. */
    data class ToolCallRequested(val call: ToolCall) : ChatEvent

    /** The answer ended: always the last event of a successful stream. */
    data class Finished(val reason: FinishReason, val usage: TokenUsage? = null) : ChatEvent
}

/** Why the model stopped. */
enum class FinishReason {
    /** It finished its answer. */
    STOP,

    /** It stopped to wait for the results of its tool calls. */
    TOOL_CALLS,

    /** It hit the token limit, so the text may be cut. */
    LENGTH,

    /** The provider's content filter stopped it. */
    CONTENT_FILTER,

    /** Any other reason the provider reports. */
    OTHER,
}

/** Tokens a request used (RF-IA-07); [cachedPromptTokens] are the prompt tokens served from cache. */
data class TokenUsage(val promptTokens: Int, val completionTokens: Int, val cachedPromptTokens: Int = 0) {
    init {
        require(promptTokens >= 0 && completionTokens >= 0 && cachedPromptTokens >= 0) { "Token counts cannot be negative" }
        require(cachedPromptTokens <= promptTokens) { "Cached tokens are part of the prompt tokens" }
    }

    val totalTokens: Int get() = promptTokens + completionTokens

    operator fun plus(other: TokenUsage) = TokenUsage(
        promptTokens + other.promptTokens,
        completionTokens + other.completionTokens,
        cachedPromptTokens + other.cachedPromptTokens,
    )
}

/** A whole answer, once its stream ended: what the caller keeps and appends to the conversation. */
data class ChatReply(
    val text: String,
    val toolCalls: List<ToolCall>,
    val finishReason: FinishReason,
    val usage: TokenUsage?,
    val reasoning: String = "",
) {
    /** The answer as the assistant message of the conversation, or null when it was empty. */
    fun toMessage(): ChatMessage.Assistant? = if (text.isBlank() && toolCalls.isEmpty()) {
        null
    } else {
        ChatMessage.Assistant(text.ifBlank { null }, toolCalls, reasoning.ifBlank { null })
    }
}

/**
 * Collects a stream into one [ChatReply], calling [onText] with each piece of text as it arrives and
 * [onReasoning] with each piece of reasoning. Fails with [AiErrorKind.MALFORMED_RESPONSE] if the
 * stream ends without [ChatEvent.Finished] or sends events after it.
 */
suspend fun Flow<ChatEvent>.collectReply(onReasoning: (String) -> Unit = {}, onText: (String) -> Unit = {}): ChatReply {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    var finished: ChatEvent.Finished? = null
    collect { event ->
        if (finished != null) throw AiException(AiErrorKind.MALFORMED_RESPONSE, "The provider sent events after the end")
        when (event) {
            is ChatEvent.TextDelta -> {
                text.append(event.text)
                onText(event.text)
            }
            is ChatEvent.ReasoningDelta -> {
                reasoning.append(event.text)
                onReasoning(event.text)
            }
            is ChatEvent.ToolCallRequested -> calls += event.call
            is ChatEvent.Finished -> finished = event
        }
    }
    val end = finished ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "The answer ended without finishing")
    return ChatReply(text.toString(), calls, end.reason, end.usage, reasoning.toString())
}
