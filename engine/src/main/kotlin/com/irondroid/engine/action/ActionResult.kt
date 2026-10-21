package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One value that an action changed, such as health from 5 to 3. Null means it did not exist before or after. */
data class StateChange(val field: String, val before: JsonElement, val after: JsonElement) {
    constructor(field: String, before: Int?, after: Int?) : this(field, JsonPrimitive(before), JsonPrimitive(after))

    constructor(field: String, before: String?, after: String?) : this(field, JsonPrimitive(before), JsonPrimitive(after))

    constructor(field: String, before: Boolean?, after: Boolean?) : this(field, JsonPrimitive(before), JsonPrimitive(after))

    fun toJson(): JsonObject = buildJsonObject {
        put("field", field)
        put("before", before)
        put("after", after)
    }
}

/**
 * What every action gives back (RF-API-06): [data] such as the roll and its outcome, a short
 * [summary] for the narration, the [changes] to the game, and a [pendingChoice] when the player
 * has to decide before the action can go on (RF-API-07).
 */
data class ActionResult(
    val summary: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val changes: List<StateChange> = emptyList(),
    val pendingChoice: PendingChoice? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("summary", summary)
        put("data", data)
        put("changes", JsonArray(changes.map { it.toJson() }))
        put("pending_choice", pendingChoice?.toJson() ?: JsonNull)
    }
}

/** One option of a [PendingChoice]: [id] to pick it, [text] to describe it. */
data class ChoiceOption(val id: String, val text: String)

/**
 * What the AI suggests picking, and [why] (RF-ENV-11). [proposal] is the concrete outcome it proposes
 * for an open-ended option, such as the consequence of Pay the Price (RF-ENV-07).
 */
data class Suggestion(val optionIds: List<String>, val why: String, val proposal: String? = null)

/**
 * A decision only the player makes (RF-API-07): pick [count] of [options]. The AI may mark a
 * [suggestion] with `suggest_option`; it never decides. [resume] goes on with the picked option
 * ids, which [PendingChoices.decide] has already checked, and returns the rest of the action.
 */
class PendingChoice(
    val prompt: String,
    val options: List<ChoiceOption>,
    val count: Int = 1,
    /** The move that asks, so a screen can name it in the player's language; null for other choices. */
    val move: String? = null,
    private val resume: (List<String>) -> ActionResult,
) {
    init {
        require(options.isNotEmpty()) { "A choice needs options" }
        require(options.map { it.id }.toSet().size == options.size) { "Option ids must be unique" }
        require(count in 1..options.size) { "Cannot pick $count of ${options.size} options" }
    }

    /** Set by [PendingChoices] when the choice opens. */
    var id: String = ""
        internal set

    var suggestion: Suggestion? = null
        internal set

    internal fun resume(picked: List<String>) = resume.invoke(picked)

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("prompt", prompt)
        put("count", count)
        put("move", move?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "options",
            JsonArray(
                options.map { option ->
                    buildJsonObject {
                        put("id", option.id)
                        put("text", option.text)
                    }
                },
            ),
        )
        put(
            "suggestion",
            suggestion?.let { s ->
                buildJsonObject {
                    put("option_ids", JsonArray(s.optionIds.map(::JsonPrimitive)))
                    put("why", s.why)
                    s.proposal?.let { put("proposal", it) }
                }
            } ?: JsonNull,
        )
    }
}
