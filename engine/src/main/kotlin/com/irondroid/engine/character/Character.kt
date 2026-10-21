package com.irondroid.engine.character

import com.irondroid.engine.debility.Debilities
import com.irondroid.engine.meters.Meters
import com.irondroid.engine.momentum.Momentum
import com.irondroid.engine.move.RollSource
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrack
import com.irondroid.engine.progress.ProgressTrackType
import kotlinx.serialization.Serializable

/** A bonus waiting for the next action roll, or only for the next time [move] is made. */
@Serializable
data class PendingBonus(val amount: Int, val move: String? = null)

/**
 * The minimal character sheet the engine plays moves on. Immutable: every change returns a new one.
 * Momentum always matches the number of marked debilities.
 */
@Serializable
data class Character(
    val stats: Map<Stat, Int>,
    val meters: Meters = Meters(),
    val momentum: Momentum = Momentum(),
    val debilities: Debilities = Debilities(),
    val tracks: Map<String, ProgressTrack> = emptyMap(),
    val experience: Int = 0,
    val pendingBonuses: List<PendingBonus> = emptyList(),
    val isEnded: Boolean = false,
) {
    init {
        require(stats.keys == Stat.entries.toSet()) { "Every stat needs a value, got ${stats.keys}" }
        require(stats.values.all { it >= 0 }) { "Stats cannot be negative: $stats" }
        require(momentum.debilities == debilities.count) {
            "Momentum counts ${momentum.debilities} debilities but ${debilities.count} are marked"
        }
        require(experience >= 0) { "Experience cannot be negative, was $experience" }
    }

    /** Value an action roll adds for [source]: the stat or the current meter. */
    fun value(source: RollSource): Int = source.stat?.let { stats.getValue(it) } ?: meters[checkNotNull(source.meter)].value

    /** Same character with [debilities], and momentum adjusted to them. */
    fun withDebilities(debilities: Debilities): Character =
        copy(debilities = debilities, momentum = debilities.applyTo(momentum))

    /** Adds a new track and returns its id together with the updated character. */
    fun startTrack(type: ProgressTrackType, name: String, rank: ChallengeRank?): Pair<String, Character> {
        val prefix = type.name.lowercase()
        val number = (tracks.keys.mapNotNull { it.removePrefix("${prefix}_").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val id = "${prefix}_$number"
        return id to copy(tracks = tracks + (id to ProgressTrack(name, type, rank)))
    }

    companion object {
        /** A new character with the given stats and everything else at its starting value. */
        fun create(edge: Int, heart: Int, iron: Int, shadow: Int, wits: Int): Character = Character(
            mapOf(Stat.EDGE to edge, Stat.HEART to heart, Stat.IRON to iron, Stat.SHADOW to shadow, Stat.WITS to wits),
        )
    }
}
