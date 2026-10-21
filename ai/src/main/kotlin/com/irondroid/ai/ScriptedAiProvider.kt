package com.irondroid.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A fake provider that answers from a script, without network (IRON-46). Tests and screens under
 * development use it to drive the narrator with known answers.
 *
 * Each request takes the next [ScriptedTurn]; [requests] keeps what was sent so tests can check it.
 */
class ScriptedAiProvider(
    turns: List<ScriptedTurn>,
    override val info: ProviderInfo = INFO,
) : AiProvider {
    private val pending = ArrayDeque(turns)
    private val received = mutableListOf<ChatRequest>()

    /** The requests collected so far, in order. */
    val requests: List<ChatRequest> get() = received.toList()

    /** How many scripted turns are left. */
    val remaining: Int get() = pending.size

    override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
        received += request
        val turn = pending.removeFirstOrNull() ?: error("The script has no answer left for request ${received.size}")
        turn.events.forEach { emit(it) }
        turn.failure?.let { throw it }
    }

    companion object {
        val INFO = ProviderInfo(id = "scripted", name = "Scripted", models = listOf(ModelInfo("scripted")), needsApiKey = false)
    }
}

/** One scripted answer: the [events] to stream and, optionally, a [failure] thrown after them. */
class ScriptedTurn(val events: List<ChatEvent>, val failure: AiException? = null) {
    companion object {
        /** A text answer streamed in pieces of [chunkSize] characters. */
        fun text(text: String, chunkSize: Int = 8, usage: TokenUsage? = null): ScriptedTurn {
            require(chunkSize > 0) { "chunkSize must be positive" }
            return ScriptedTurn(text.chunked(chunkSize).map { ChatEvent.TextDelta(it) } + ChatEvent.Finished(FinishReason.STOP, usage))
        }

        /** An answer that calls [calls], optionally after some [text]. */
        fun toolCalls(vararg calls: ToolCall, text: String? = null, usage: TokenUsage? = null) = ScriptedTurn(
            listOfNotNull(text?.let { ChatEvent.TextDelta(it) }) +
                calls.map { ChatEvent.ToolCallRequested(it) } +
                ChatEvent.Finished(FinishReason.TOOL_CALLS, usage),
        )

        /** A request that fails with [error], after streaming [partialText] if given. */
        fun failure(error: AiException, partialText: String? = null) =
            ScriptedTurn(listOfNotNull(partialText?.let { ChatEvent.TextDelta(it) }), error)
    }
}
