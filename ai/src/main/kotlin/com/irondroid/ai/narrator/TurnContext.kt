package com.irondroid.ai.narrator

/**
 * What the narrator gets at the start of every turn, so it keeps the story straight without asking
 * (RF-ENV-08, RF-CAM-03): the character as it stands now, its vows and other tracks, the world's
 * truths and the facts the oracle tables established. A minimal campaign memory for the MVP; the
 * full one (summaries, notable people and places) comes later.
 */
data class TurnContext(
    val characterName: String,
    /** Stat name to value, such as "edge" to 2. */
    val stats: Map<String, Int>,
    val health: Int,
    val spirit: Int,
    val supply: Int,
    val momentum: Int,
    val debilities: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
    /** The world truths of the campaign, as written. */
    val truths: List<String> = emptyList(),
    /** Oracle results of past turns, oldest first, such as "Ironlander Names (1-100): Kaivan". */
    val facts: List<String> = emptyList(),
) {
    /** A progress track: a vow, a journey, a fight or a bond. */
    data class Track(val name: String, val type: String, val rank: String?, val boxes: Int)

    /** The context as a model-facing note, in English like the narrator prompt. */
    fun render(): String = buildString {
        append("## Current state (read it, do not repeat it)\n\n")
        append("$characterName: ")
        append(stats.entries.joinToString(", ") { "${it.key} ${it.value}" })
        append("; health $health, spirit $spirit, supply $supply, momentum ${if (momentum > 0) "+" else ""}$momentum")
        if (debilities.isNotEmpty()) append("; debilities: ${debilities.joinToString(", ")}")
        append(".\n")
        if (stats.values.all { it == 0 }) {
            append("The stats are not chosen yet (guided character creation comes later): every roll adds 0.\n")
        }

        append("\n### Vows and other tracks\n\n")
        if (tracks.isEmpty()) {
            append("None yet. When the story gives $characterName a clear purpose, a vow fits.\n")
        } else {
            tracks.forEach { append("- ${it.name} (${it.type}${it.rank?.let { rank -> ", $rank" } ?: ""}): ${it.boxes}/10 boxes\n") }
        }

        if (truths.isNotEmpty()) {
            append("\n### World truths\n\n")
            truths.forEach { append("- $it\n") }
        }

        if (facts.isNotEmpty()) {
            append("\n### Established by the oracles\n\n")
            append("People, places and events already in the story. Use them again before inventing new ones.\n\n")
            facts.forEach { append("- $it\n") }
        }
    }

    companion object {
        /** Most oracle facts sent; older ones are reached with `search_journal`. */
        const val MAX_FACTS = 40
    }
}
