package com.irondroid.ai.tools

import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ToolCall
import com.irondroid.ai.ToolSpec
import com.irondroid.engine.action.ActionDefinition
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.action.toJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The action catalog as tools for the AI (RF-API-12, IRON-48). The catalog is defined once in the
 * engine; this turns each action into a provider-neutral [ToolSpec] that every adapter translates
 * to its own function calling format, and runs the AI's tool calls through the same
 * [ActionDispatcher] the screens use (RF-API-04), so the AI gets the same checks (RF-API-05).
 *
 * The read-only resources (RF-API-03) are not separate tools: each one already has a read action
 * (`get_character`, `get_tracks`, `get_scene`, `search_journal`, `get_rules`).
 */
class ActionTools(
    private val dispatcher: ActionDispatcher,
    /** Actions the AI never gets, because only the player may run them. */
    hidden: Set<String> = PLAYER_ONLY,
) {
    /** The actions offered to the AI, in catalog order. */
    val actions: List<ActionDefinition> = dispatcher.catalog.actions.filter { it.name !in hidden }

    /** The tools to put in every request. */
    val specs: List<ToolSpec> = actions.map(::toolSpec)

    /**
     * Runs [call] as the AI and gives back what to send to the model: the result, an explained
     * error, or a note that the player has to confirm the action first. It never throws for a bad
     * call, because the model should read the error and try again.
     */
    fun run(call: ToolCall): ToolRun {
        val result = dispatcher.dispatch(call.name, call.arguments, Caller.AI)
        return ToolRun(call, result, ChatMessage.ToolResult(call.id, toolOutput(result).toString()))
    }

    companion object {
        /** `choose_option`: only the player decides a choice (RF-ENV-11); the AI marks its pick with `suggest_option`. */
        val PLAYER_ONLY = setOf("choose_option")

        fun toolSpec(action: ActionDefinition) = ToolSpec(action.name, action.fullDescription, action.parameters.toJson())

        /** What the model reads after a call: always a JSON object with a `status`. */
        fun toolOutput(result: DispatchResult): JsonObject = when (result) {
            is DispatchResult.Success -> buildJsonObject {
                put("status", "ok")
                result.result.toJson().forEach { (key, value) -> put(key, value) }
            }
            is DispatchResult.Failure -> buildJsonObject {
                put("status", "error")
                put("error", result.error.toJson())
            }
            is DispatchResult.NeedsConfirmation -> buildJsonObject {
                put("status", "needs_confirmation")
                put(
                    "message",
                    "${result.action.name} has NOT run: the player must confirm it first. " +
                        "Tell the player what you want to do and why, and wait for their answer.",
                )
            }
        }
    }
}

/** One tool call of the AI after it ran: what the dispatcher did and the [message] to send back to the model. */
data class ToolRun(val call: ToolCall, val result: DispatchResult, val message: ChatMessage.ToolResult)
