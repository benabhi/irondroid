package com.irondroid.ai.narrator

import com.irondroid.ai.AiException
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.ToolSpec
import com.irondroid.ai.collectReply
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A move the suggester can pick: its [id] and the [trigger] that says when it is made. */
data class MoveOption(val id: String, val trigger: String)

/**
 * The moves the AI suggests for the scene when the player opens the move picker (IRON-64, RF-UI-11):
 * one small request apart from the turn, so the narration is not slowed down. The model answers by
 * calling a tool with up to [MAX] move ids; anything else, or a failure, suggests nothing.
 */
class MoveSuggester(
    private val provider: AiProvider,
    private val model: String,
    private val moves: List<MoveOption>,
) {
    private val ids = moves.map { it.id }.toSet()

    private val tool = ToolSpec(
        name = TOOL,
        description = "Gives the moves the scene most likely triggers next, best first.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("moves") {
                    put("type", "array")
                    put("maxItems", MAX)
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") { moves.forEach { add(JsonPrimitive(it.id)) } }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("moves")) }
            put("additionalProperties", false)
        },
    )

    /** Up to [MAX] move ids for the scene that [narration] left and the player's [input], best first; empty if the AI fails. */
    suspend fun suggest(narration: String, input: String?): List<String> {
        if (narration.isBlank() && input.isNullOrBlank()) return emptyList()
        val scene = buildString {
            input?.takeIf { it.isNotBlank() }?.let { append("The player said: ").append(it).append("\n\n") }
            append("The narrator answered: ").append(narration)
        }
        val messages = listOf(
            ChatMessage.System(INSTRUCTIONS + moves.joinToString("\n") { "- ${it.id}: ${it.trigger.lineSequence().first()}" }),
            ChatMessage.User(scene),
        )
        return try {
            val reply = provider.chat(ChatRequest(model, messages, listOf(tool), reasoningEffort = ReasoningEffort.LOW)).collectReply()
            val picked = reply.toolCalls.firstOrNull { it.name == TOOL }?.parsedArguments()?.get("moves") as? JsonArray
            picked.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it in ids }.distinct().take(MAX)
        } catch (_: AiException) {
            emptyList()
        }
    }

    companion object {
        const val MAX = 3
        private const val TOOL = "suggest_moves"
        private const val INSTRUCTIONS =
            "You help a player of the Ironsworn roleplaying game choose a move. Read the scene and call suggest_moves " +
                "with the moves the fiction most likely triggers next, best first, at most $MAX. Do not write text.\n\nMoves:\n"
    }
}
