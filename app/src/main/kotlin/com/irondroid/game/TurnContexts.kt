package com.irondroid.game

import com.irondroid.ai.narrator.TurnContext
import com.irondroid.data.WorldTruth
import com.irondroid.engine.action.CallOutcome
import com.irondroid.engine.action.LoggedCall
import com.irondroid.engine.character.Character
import com.irondroid.engine.oracle.OracleTableCatalog
import com.irondroid.engine.truth.TruthCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The state the narrator reads at the start of every turn (IRON-56): [sheet] of [characterName],
 * the world's [truths] (in book order, the book's ones in English), and the oracle results (tables and yes/no answers) among [calls] that were not undone, newest
 * last. Table names go in English, like the rest of what the model reads; results stay as rolled.
 */
fun turnContext(
    characterName: String,
    sheet: Character,
    truths: List<WorldTruth>,
    calls: List<LoggedCall<Character>>,
    oracles: OracleTableCatalog,
    truthCatalog: TruthCatalog,
): TurnContext {
    val facts = calls
        .filter { (it.name == "roll_oracle_table" || it.name == "ask_oracle") && it.outcome == CallOutcome.SUCCESS && !it.undone }
        .mapNotNull { call ->
            val data = call.details["data"] as? JsonObject
            val result = call.details["summary"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // A yes/no answer keeps its question, also when the player asked it without narration (IRON-65).
            if (call.name == "ask_oracle") {
                val question = data?.get("question")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                return@mapNotNull "Oracle, \"$question\": $result"
            }
            val table = data?.get("table")?.jsonPrimitive?.contentOrNull
            val name = table?.let { oracles.text(it, "en")?.name } ?: table
            if (name != null) "$name: $result" else result
        }
        .takeLast(TurnContext.MAX_FACTS)
    return TurnContext(
        characterName = characterName,
        stats = sheet.stats.entries.associate { it.key.name.lowercase() to it.value },
        health = sheet.meters.health.value,
        spirit = sheet.meters.spirit.value,
        supply = sheet.meters.supply.value,
        momentum = sheet.momentum.value,
        debilities = sheet.debilities.marked.map { it.name.lowercase() },
        tracks = sheet.tracks.values.map { track ->
            TurnContext.Track(track.name, track.type.name.lowercase(), track.rank?.name?.lowercase(), track.filledBoxes)
        },
        truths = worldTruths(truths, truthCatalog),
        facts = facts,
    )
}

/** Each truth as "Category: text", in book order. The book's options go in English; the player's own stay as written. */
internal fun worldTruths(truths: List<WorldTruth>, catalog: TruthCatalog): List<String> {
    val order = catalog.categories.map { it.id }
    return truths.sortedBy { order.indexOf(it.truthKey).let { index -> if (index < 0) order.size else index } }.map { truth ->
        val name = catalog.text(truth.truthKey, "en")?.name ?: truth.truthKey
        val text = truth.optionId?.let { catalog.optionText(truth.truthKey, it, "en")?.text } ?: truth.text
        "$name: $text"
    }
}
