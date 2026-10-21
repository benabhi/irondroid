package com.irondroid.engine.action

import com.irondroid.engine.character.Character
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.move.MoveCatalog
import com.irondroid.engine.move.MoveRoll
import com.irondroid.engine.oracle.OracleTableCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The standard read-only resources of a game (RF-API-03) and the JSON views they return. Readers
 * take the state through functions, so they always show the current game and know nothing about
 * where it is stored. Rule texts come in [language], the player's language.
 */
object GameResources {
    const val CHARACTER = "irondroid://character"
    const val TRACKS = "irondroid://tracks"
    const val SCENE = "irondroid://scene"
    const val JOURNAL = "irondroid://journal"
    const val MOVES = "irondroid://rules/moves"
    const val ORACLES = "irondroid://rules/oracles"

    /** How many recent calls the journal shows. */
    const val JOURNAL_SIZE = 30

    fun create(
        characterName: () -> String,
        sheet: () -> Character,
        calls: () -> List<LoggedCall<*>>,
        moves: MoveCatalog,
        oracles: OracleTableCatalog,
        language: String,
    ): ResourceCatalog = ResourceCatalog.of(
        listOf(
            ResourceDefinition(CHARACTER, "Character sheet", "The character: stats, meters, momentum, debilities, experience and pending roll bonuses.") to
                ResourceReader { character(characterName(), sheet()) },
            ResourceDefinition(TRACKS, "Progress tracks", "Every progress track (vows, journeys, combat, bonds) with its rank and progress.") to
                ResourceReader { tracks(sheet()) },
            ResourceDefinition(SCENE, "Current scene", "Where the character is and what is happening now.") to
                ResourceReader { scene() },
            ResourceDefinition(JOURNAL, "Journal", "The most recent things done in the game, oldest first, with who did them.") to
                ResourceReader { journal(calls()) },
            ResourceDefinition(MOVES, "Moves", "Every move with its trigger, how it is rolled and what each outcome means, in the player's language.") to
                ResourceReader { moves(moves, language) },
            ResourceDefinition(ORACLES, "Oracle tables", "Every oracle table with its rows, in the player's language.") to
                ResourceReader { oracles(oracles, language) },
        ),
    )

    fun character(name: String, sheet: Character): JsonObject = buildJsonObject {
        put("name", name)
        put("stats", JsonObject(sheet.stats.entries.sortedBy { it.key.ordinal }.associate { it.key.name.lowercase() to JsonPrimitive(it.value) }))
        put("meters", JsonObject(MeterType.entries.associate { it.name.lowercase() to JsonPrimitive(sheet.meters[it].value) }))
        put(
            "momentum",
            buildJsonObject {
                put("value", sheet.momentum.value)
                put("max", sheet.momentum.max)
                put("reset", sheet.momentum.reset)
            },
        )
        put("debilities", JsonArray(sheet.debilities.marked.sortedBy { it.ordinal }.map { JsonPrimitive(it.name.lowercase()) }))
        put("experience", sheet.experience)
        put(
            "pending_bonuses",
            JsonArray(
                sheet.pendingBonuses.map { bonus ->
                    buildJsonObject {
                        put("amount", bonus.amount)
                        put("move", bonus.move?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            ),
        )
        put("is_ended", sheet.isEnded)
    }

    fun tracks(sheet: Character): JsonObject = buildJsonObject {
        put(
            "tracks",
            JsonArray(
                sheet.tracks.entries.sortedBy { it.key }.map { (id, track) ->
                    buildJsonObject {
                        put("id", id)
                        put("name", track.name)
                        put("type", track.type.name.lowercase())
                        put("rank", track.rank?.name?.lowercase()?.let(::JsonPrimitive) ?: JsonNull)
                        put("ticks", track.ticks)
                        put("filled_boxes", track.filledBoxes)
                        put("complete", track.isComplete)
                    }
                },
            ),
        )
    }

    /** There is no scene in the game yet: the AI is told so instead of getting made-up data. */
    fun scene(): JsonObject = buildJsonObject {
        put("scene", JsonNull)
        put("note", "The app does not track scenes yet; rely on the journal and the conversation.")
    }

    fun journal(calls: List<LoggedCall<*>>): JsonObject = buildJsonObject {
        val done = calls.filter { it.outcome == CallOutcome.SUCCESS && !it.undone }
        put(
            "entries",
            JsonArray(
                done.takeLast(JOURNAL_SIZE).map { call ->
                    buildJsonObject {
                        put("sequence", call.sequence)
                        put("action", call.name)
                        put("by", call.caller.name.lowercase())
                        put("summary", call.details["summary"] ?: JsonNull)
                    }
                },
            ),
        )
        put("older_entries", maxOf(0, done.size - JOURNAL_SIZE))
    }

    fun moves(catalog: MoveCatalog, language: String): JsonObject = buildJsonObject {
        put("language", language)
        put(
            "moves",
            JsonArray(
                catalog.moves.map { move ->
                    val text = checkNotNull(catalog.text(move.id, language)) { "Move ${move.id} has no $language text" }
                    buildJsonObject {
                        put("id", move.id)
                        put("name", text.name)
                        put("category", move.category.name.lowercase())
                        put("trigger", text.trigger)
                        put(
                            "roll",
                            when (val roll = move.roll) {
                                is MoveRoll.Action -> buildJsonObject {
                                    put("type", "action")
                                    put("sources", JsonArray(roll.sources.map { JsonPrimitive(it.name.lowercase()) }))
                                    put("pick", roll.pick.name.lowercase())
                                }
                                is MoveRoll.Progress -> buildJsonObject {
                                    put("type", "progress")
                                    put("track", roll.track.name.lowercase())
                                }
                                MoveRoll.None -> buildJsonObject { put("type", "none") }
                            },
                        )
                        text.strongHit?.let { put("strong_hit", it) }
                        text.weakHit?.let { put("weak_hit", it) }
                        text.miss?.let { put("miss", it) }
                        if (text.options.isNotEmpty()) put("options", JsonObject(text.options.mapValues { JsonPrimitive(it.value) }))
                        if (text.bonuses.isNotEmpty()) put("bonuses", JsonObject(text.bonuses.mapValues { JsonPrimitive(it.value) }))
                    }
                },
            ),
        )
    }

    fun oracles(catalog: OracleTableCatalog, language: String): JsonObject = buildJsonObject {
        put("language", language)
        put(
            "tables",
            JsonArray(
                catalog.tables.map { table ->
                    val text = checkNotNull(catalog.text(table.id, language)) { "Table ${table.id} has no $language text" }
                    buildJsonObject {
                        put("id", table.id)
                        put("name", text.name)
                        put(
                            "rows",
                            JsonArray(
                                table.rows.map { row ->
                                    buildJsonObject {
                                        put("min", row.min)
                                        put("max", row.max)
                                        put("text", text.rows.getValue(row.id))
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
        )
    }
}
