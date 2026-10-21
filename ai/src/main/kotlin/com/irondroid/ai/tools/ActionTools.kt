package com.irondroid.ai.tools

import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ToolCall
import com.irondroid.ai.ToolSpec
import com.irondroid.engine.action.ActionCall
import com.irondroid.engine.action.ActionDefinition
import com.irondroid.engine.action.ActionError
import com.irondroid.engine.action.ActionErrorCode
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

    private val offered = actions.map { it.name }.toSet()

    /**
     * Runs [call] as the AI and gives back what to send to the model: the result, an explained
     * error, or a note that the player has to confirm the action first. It never throws for a bad
     * call, because the model should read the error and try again.
     */
    fun run(call: ToolCall): ToolRun {
        val result = if (call.name in offered || dispatcher.catalog[call.name] == null) {
            dispatcher.dispatch(call.name, call.arguments, Caller.AI)
        } else {
            // An action of the catalog that is not among these tools: the model must not get around the list.
            DispatchResult.Failure(
                ActionCall(call.name, call.parsedArguments() ?: JsonObject(emptyMap()), Caller.AI),
                ActionError(ActionErrorCode.UNKNOWN_ACTION, "${call.name} is not one of your tools", suggestion = "Use only the tools you were given"),
            )
        }
        return ToolRun(call, result, ChatMessage.ToolResult(call.id, toolOutput(result).toString()))
    }

    companion object {
        /** `choose_option`: only the player decides a choice (RF-ENV-11); the AI marks its pick with `suggest_option`. */
        val PLAYER_ONLY = setOf("choose_option")

        /**
         * The actions the narrator uses while playing (IRON-52). Leaving the rest out keeps each
         * request smaller and faster, and keeps the AI away from the sheet: creating or editing the
         * character, changing meters or debilities by hand, burning momentum and saving are the
         * player's, and the rules and the table list are already in the tools' own descriptions.
         */
        val NARRATOR = setOf(
            "get_character", "get_tracks", "get_scene", "search_journal", "get_move",
            "roll_move", "suggest_option", "roll_progress", "create_progress_track", "mark_progress",
            "ask_oracle", "roll_oracle_table",
        )

        /** The tools of the narrator: only the [NARRATOR] actions of [dispatcher]. */
        fun forNarrator(dispatcher: ActionDispatcher) =
            ActionTools(dispatcher, hidden = dispatcher.catalog.actions.map { it.name }.toSet() - NARRATOR)

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
