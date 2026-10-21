package com.irondroid.engine.game

import com.irondroid.engine.action.StateChange
import com.irondroid.engine.character.Character
import com.irondroid.engine.character.Stat
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.progress.ProgressTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What differs between two versions of a character sheet, as the changes an action reports (RF-API-06). */
object SheetChanges {
    fun between(before: Character, after: Character): List<StateChange> = buildList {
        for (stat in Stat.entries) {
            int("stats.${stat.name.lowercase()}", before.stats.getValue(stat), after.stats.getValue(stat))
        }
        for (meter in MeterType.entries) int(meter.name.lowercase(), before.meters[meter].value, after.meters[meter].value)
        int("momentum", before.momentum.value, after.momentum.value)
        int("experience", before.experience, after.experience)
        if (before.debilities != after.debilities) {
            add(StateChange("debilities", names(before), names(after)))
        }
        if (before.pendingBonuses != after.pendingBonuses) {
            add(StateChange("pending_bonuses", JsonPrimitive(before.pendingBonuses.size), JsonPrimitive(after.pendingBonuses.size)))
        }
        for (id in (before.tracks.keys + after.tracks.keys).sorted()) {
            val old = before.tracks[id]
            val new = after.tracks[id]
            when {
                old == new -> Unit
                old == null || new == null -> add(StateChange("tracks.$id", track(old), track(new)))
                else -> {
                    int("tracks.$id.ticks", old.ticks, new.ticks)
                    if (old.rank != new.rank) add(StateChange("tracks.$id.rank", old.rank?.name?.lowercase(), new.rank?.name?.lowercase()))
                }
            }
        }
        if (before.isEnded != after.isEnded) add(StateChange("is_ended", before.isEnded, after.isEnded))
    }

    private fun MutableList<StateChange>.int(field: String, before: Int, after: Int) {
        if (before != after) add(StateChange(field, before, after))
    }

    private fun names(sheet: Character) = JsonArray(sheet.debilities.marked.sortedBy { it.ordinal }.map { JsonPrimitive(it.name.lowercase()) })

    private fun track(track: ProgressTrack?): JsonElement =
        if (track == null) {
            JsonNull
        } else {
            buildJsonObject {
                put("name", track.name)
                put("type", track.type.name.lowercase())
                put("rank", track.rank?.name?.lowercase()?.let(::JsonPrimitive) ?: JsonNull)
                put("ticks", track.ticks)
            }
        }
}
