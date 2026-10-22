package com.irondroid.ai.world

import com.irondroid.ai.AiException
import com.irondroid.ai.AiProvider
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.ToolSpec
import com.irondroid.ai.collectReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A truth category the suggester can pick from: its [id], its [name] and its [options] (id to text), in English. */
data class TruthChoices(val id: String, val name: String, val options: Map<String, String>)

/** The option the AI suggests for a category, and the short [reason] it gives in the player's language. */
data class TruthSuggestion(val optionId: String, val reason: String?)

/**
 * The truths the AI suggests when a new world is created (IRON-77, RF-CAM-01): one small request apart
 * from the game, for every category at once, so the choices fit together and the campaign. The model
 * answers by calling a tool with one option per category; anything else, or a failure, suggests nothing.
 */
class TruthSuggester(
    private val provider: AiProvider,
    private val model: String,
    private val categories: List<TruthChoices>,
) {
    private val tool = ToolSpec(
        name = TOOL,
        description = "Gives one option for every truth category, with a short reason for each.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for (category in categories) {
                    putJsonObject(category.id) {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("option") {
                                put("type", "string")
                                putJsonArray("enum") { category.options.keys.forEach { add(JsonPrimitive(it)) } }
                            }
                            putJsonObject("reason") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("option"))
                            add(JsonPrimitive("reason"))
                        }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { categories.forEach { add(JsonPrimitive(it.id)) } }
            put("additionalProperties", false)
        },
    )

    /**
     * One suggestion per category id for a campaign called [campaignTitle] with [characterName] in a world
     * called [worldName]; the reasons are written in [language] (such as "es"). Empty if the AI fails, after
     * telling [onFailure]; categories it skipped or answered with an unknown option are left out.
     */
    suspend fun suggest(
        campaignTitle: String,
        characterName: String,
        worldName: String,
        language: String,
        onFailure: (AiException) -> Unit = {},
    ): Map<String, TruthSuggestion> {
        val messages = listOf(
            ChatMessage.System(instructions(language)),
            ChatMessage.User("Campaign: $campaignTitle\nCharacter: $characterName\nWorld: $worldName"),
        )
        return try {
            val reply = provider.chat(ChatRequest(model, messages, listOf(tool), reasoningEffort = ReasoningEffort.LOW)).collectReply()
            val picked = reply.toolCalls.firstOrNull { it.name == TOOL }?.parsedArguments() ?: return emptyMap()
            categories.mapNotNull { category ->
                val answer = picked[category.id] as? JsonObject ?: return@mapNotNull null
                val option = (answer["option"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in category.options } ?: return@mapNotNull null
                val reason = (answer["reason"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                category.id to TruthSuggestion(option, reason)
            }.toMap()
        } catch (e: AiException) {
            onFailure(e)
            emptyMap()
        }
    }

    private fun instructions(language: String) = buildString {
        append(
            "You help a player of the Ironsworn roleplaying game set the truths of a new world. For every category, " +
                "pick the option that best fits the campaign and the other picks, so the world hangs together and " +
                "promises good stories. Call $TOOL once with every category. Give each pick a reason of one short " +
                "sentence, at most $REASON_WORDS words, written in the language with code \"$language\". Do not write text.\n\n",
        )
        for (category in categories) {
            append("## ").append(category.name).append(" (").append(category.id).append(")\n")
            category.options.forEach { (id, text) -> append("- ").append(id).append(": ").append(text).append('\n') }
            append('\n')
        }
    }

    companion object {
        private const val TOOL = "suggest_truths"
        private const val REASON_WORDS = 20
    }
}
