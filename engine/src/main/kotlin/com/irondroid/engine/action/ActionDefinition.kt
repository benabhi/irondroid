package com.irondroid.engine.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The contract of the actions interface (RF-API-02, RF-API-08). The same actions serve the app's
 * screens and the AI (RF-API-04), so each one describes itself well enough for an AI that knows
 * nothing about the code. Descriptions are in English: they are read by the AI, not shown to the
 * player; the app takes what the player sees from its localized resources.
 */

/** Groups of the catalog (SRD 3.14). */
enum class ActionArea { QUERY, CHARACTER, MOVES, STATE, PROGRESS, COMBAT, ORACLES, CAMPAIGN, SYSTEM }

/** Who may run an action freely (RF-API-08). */
enum class ActionPermission(
    /** The player confirms before it runs. */
    val requiresConfirmation: Boolean,
    /** It can be reverted from the turn history (RF-API-10). */
    val undoable: Boolean,
) {
    /** Only reads: changes nothing. */
    READ(requiresConfirmation = false, undoable = false),

    /** Plays the game: rolls, marks, adjusts. */
    PLAY(requiresConfirmation = false, undoable = true),

    /** Deletes data, edits the sheet by hand or changes settings. */
    SENSITIVE(requiresConfirmation = true, undoable = false),
}

/** What running an action can change or cause, so callers know what to save, log and undo. */
enum class ActionEffect(val changesState: Boolean, val summary: String) {
    ROLLS_DICE(false, "rolls dice"),
    CHANGES_CHARACTER(true, "changes the character sheet"),
    CHANGES_TRACKS(true, "changes progress tracks"),
    CHANGES_SCENE(true, "changes the current scene"),
    CHANGES_JOURNAL(true, "writes to the campaign journal"),
    CHANGES_CAMPAIGN(true, "changes campaign data such as NPCs and places"),
    CHANGES_SETTINGS(true, "changes app settings"),
    DELETES_DATA(true, "deletes data"),
}

/** What an action gives back: a description and, when it returns data, its [schema]. */
data class ActionReturn(val description: String, val schema: JsonSchema? = null)

/**
 * One action of the interface (RF-API-02).
 *
 * [name] is the identifier callers use, in snake_case. [description] says what it does and
 * [whenToUse] when it fits, both written for the AI. [parameters] is the JSON Schema of the
 * arguments; [returns] what comes back; [effects] what it changes; [permission] how freely it runs.
 * [answersChoice] marks the actions that work on the open pending choice (`suggest_option`,
 * `choose_option`): the only play actions allowed while the player has a choice to make.
 */
data class ActionDefinition(
    val name: String,
    val area: ActionArea,
    val description: String,
    val whenToUse: String,
    val permission: ActionPermission,
    val returns: ActionReturn,
    val parameters: JsonSchema.ObjectOf = JsonSchema.ObjectOf(),
    val effects: Set<ActionEffect> = emptySet(),
    val answersChoice: Boolean = false,
) {
    /** Whether running it changes saved state, so the game must be saved afterwards. */
    val changesState: Boolean get() = effects.any { it.changesState }

    /**
     * One text with everything the AI needs to pick the action: what it does, when to use it,
     * what it changes, whether the player confirms it and what it returns. Providers that only
     * take a description get this one.
     */
    val fullDescription: String
        get() = buildString {
            append(description.trim().withPeriod())
            append(" Use it when ").append(whenToUse.trim().replaceFirstChar { it.lowercase() }.withPeriod())
            if (effects.isEmpty()) {
                append(" It changes nothing.")
            } else {
                append(" Effects: ").append(effects.sorted().joinToString(", ") { it.summary }).append('.')
            }
            if (answersChoice) append(" It works on the choice the player has open.")
            if (permission.requiresConfirmation) append(" The player must confirm it before it runs.")
            append(" Returns: ").append(returns.description.trim().replaceFirstChar { it.lowercase() }.withPeriod())
        }

    /** Problems that make this definition unusable. Empty when it is fine. */
    fun problems(): List<String> = buildList {
        val where = "Action $name"
        if (!JsonSchema.NAME_PATTERN.matches(name)) add("$where: name must be snake_case")
        if (description.isBlank()) add("$where: needs a description")
        if (whenToUse.isBlank()) add("$where: needs to say when to use it")
        if (returns.description.isBlank()) add("$where: needs to say what it returns")
        when {
            permission == ActionPermission.READ && effects.isNotEmpty() ->
                add("$where: a read action cannot have effects")
            permission == ActionPermission.READ && answersChoice ->
                add("$where: a read action cannot answer a choice")
            permission != ActionPermission.READ && effects.isEmpty() && !answersChoice ->
                add("$where: a ${permission.name.lowercase()} action needs its effects")
        }
        if (permission != ActionPermission.SENSITIVE) {
            effects.filter { it in SENSITIVE_EFFECTS }.forEach { add("$where: ${it.summary} only in a sensitive action") }
        }
        addAll(parameters.problems("$where, parameters"))
        returns.schema?.let { addAll(it.problems("$where, returns")) }
    }

    /** The action as one entry of the published catalog (an MCP `tools/list` entry plus our metadata). */
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("area", area.name.lowercase())
        put("description", description)
        put("when_to_use", whenToUse)
        put("permission", permission.name.lowercase())
        put("requires_confirmation", permission.requiresConfirmation)
        put("undoable", permission.undoable)
        put("answers_choice", answersChoice)
        put("effects", JsonArray(effects.sorted().map { JsonPrimitive(it.name.lowercase()) }))
        put("parameters", parameters.toJson())
        put(
            "returns",
            buildJsonObject {
                put("description", returns.description)
                returns.schema?.let { put("schema", it.toJson()) }
            },
        )
    }

    private companion object {
        /** Effects the player must always confirm. */
        val SENSITIVE_EFFECTS = setOf(ActionEffect.CHANGES_SETTINGS, ActionEffect.DELETES_DATA)

        fun String.withPeriod() = if (endsWith('.') || endsWith('?') || endsWith('!')) this else "$this."
    }
}
