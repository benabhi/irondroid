package com.irondroid.ai.narrator

import com.irondroid.ai.AiException
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.TokenUsage
import com.irondroid.ai.ToolChoice
import com.irondroid.ai.collectReply
import com.irondroid.ai.tools.ActionTools
import com.irondroid.engine.action.DispatchResult

/** A turn already played, as the narrator remembers it: what the player said and the narration. */
data class PastTurn(val playerInput: String?, val narration: String?)

/** One action the narrator ran during a turn, once it ran. */
data class NarratorStep(
    val action: String,
    val arguments: String,
    /** The engine's one-line summary, or the error message. */
    val summary: String,
    val succeeded: Boolean,
)

/** What the player sees while a turn is being played. */
sealed interface TurnEvent {
    /** A piece of the narration, as it streams in. */
    data class Text(val text: String) : TurnEvent

    /** An action the narrator ran. */
    data class Step(val step: NarratorStep) : TurnEvent
}

/**
 * How a turn ended. [narration] is the whole text the narrator wrote; [error] is set when the AI
 * failed, in which case the steps already run stay done (the engine applied them).
 */
data class TurnOutcome(
    val narration: String,
    val steps: List<NarratorStep>,
    val usage: TokenUsage?,
    val firstTextMs: Long?,
    val totalMs: Long,
    val error: AiException? = null,
)

/**
 * The turn loop of the text narrator (RF-ENV-01, RF-ENV-03, IRON-52). The player's input goes to
 * the model with the narrator prompt and the recent turns; the model's tool calls run through
 * [tools] (the same dispatcher the screens use, so the engine rolls and applies every rule), their
 * results go back to the model, and the loop ends when the model answers with text only. After
 * [maxRounds] rounds of tool calls, one last request without tools makes it narrate.
 */
class Narrator(
    private val provider: AiProvider,
    private val model: String,
    private val tools: ActionTools,
    /** The narrator's system prompt for the open game (IRON-51). */
    private val prompt: () -> String,
    private val maxRounds: Int = MAX_ROUNDS,
    /** Past turns sent with each request, newest last. */
    private val memory: Int = MEMORY_TURNS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxRounds >= 1) { "The narrator needs at least one round" }
        require(memory >= 0) { "Memory cannot be negative" }
    }

    /** Plays the turn the player started with [input], after [past] turns. */
    suspend fun play(input: String, past: List<PastTurn>, onEvent: (TurnEvent) -> Unit = {}): TurnOutcome {
        require(input.isNotBlank()) { "The player has to say something" }
        return run(listOf(ChatMessage.User(input)), past, onEvent)
    }

    /**
     * Narrates what follows a choice the player just made with `choose_option` (RF-ENV-11): the
     * model gets [chosen], the text of the options picked, and the action's [result] as JSON.
     */
    suspend fun afterChoice(chosen: String, result: String, past: List<PastTurn>, onEvent: (TurnEvent) -> Unit = {}): TurnOutcome =
        run(listOf(ChatMessage.User(CHOICE_NOTE.format(chosen, result))), past, onEvent)

    private suspend fun run(newMessages: List<ChatMessage>, past: List<PastTurn>, onEvent: (TurnEvent) -> Unit): TurnOutcome {
        val started = clock()
        val conversation = mutableListOf<ChatMessage>(ChatMessage.System(prompt()))
        past.takeLast(memory).forEach { turn ->
            turn.playerInput?.takeIf { it.isNotBlank() }?.let { conversation += ChatMessage.User(it) }
            turn.narration?.takeIf { it.isNotBlank() }?.let { conversation += ChatMessage.Assistant(it) }
        }
        conversation += newMessages

        val text = StringBuilder()
        val steps = mutableListOf<NarratorStep>()
        var usage: TokenUsage? = null
        var firstText: Long? = null
        fun outcome(error: AiException? = null) =
            TurnOutcome(text.toString().trim(), steps.toList(), usage, firstText, clock() - started, error)

        try {
            for (round in 1..maxRounds + 1) {
                // Out of rounds, the model has to narrate with what it has: one last request without tools.
                val last = round > maxRounds
                // Text from a later round starts a new paragraph of the same narration.
                var separator = if (text.isEmpty()) "" else "\n\n"
                val choice = if (last) ToolChoice.NONE else ToolChoice.AUTO
                val reply = provider.chat(
                    ChatRequest(model, conversation.toList(), tools.specs, choice, reasoningEffort = ReasoningEffort.LOW),
                ).collectReply { piece ->
                    if (firstText == null) firstText = clock() - started
                    val shown = separator + piece
                    separator = ""
                    text.append(shown)
                    onEvent(TurnEvent.Text(shown))
                }
                usage = reply.usage?.let { usage?.plus(it) ?: it } ?: usage
                conversation += reply.toMessage() ?: break
                if (reply.toolCalls.isEmpty() || last) break
                for (call in reply.toolCalls) {
                    val run = tools.run(call)
                    conversation += run.message
                    val step = NarratorStep(
                        action = call.name,
                        arguments = call.arguments,
                        summary = when (val result = run.result) {
                            is DispatchResult.Success -> result.result.summary
                            is DispatchResult.Failure -> result.error.message
                            is DispatchResult.NeedsConfirmation -> "needs confirmation"
                        },
                        succeeded = run.result is DispatchResult.Success,
                    )
                    steps += step
                    onEvent(TurnEvent.Step(step))
                }
            }
        } catch (e: AiException) {
            return outcome(e)
        }
        return outcome()
    }

    companion object {
        /** Most rounds of tool calls in one turn: reading, rolling and narrating fit in a few. */
        const val MAX_ROUNDS = 6

        /** Past turns the model sees; older ones are reached with `search_journal`. */
        const val MEMORY_TURNS = 8

        /** Model-facing note for the turn after a choice. */
        private const val CHOICE_NOTE =
            "(The player chose: %s. Result of choose_option, already applied by the engine: %s) " +
                "Narrate what follows from this choice."
    }
}
