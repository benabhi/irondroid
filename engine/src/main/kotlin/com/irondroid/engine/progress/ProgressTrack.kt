package com.irondroid.engine.progress

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Rank of a challenge: how much progress each mark is worth, plus the harm and experience tied to it. */
enum class ChallengeRank(val ticksPerProgress: Int, val harm: Int, val experience: Int) {
    TROUBLESOME(12, 1, 1),
    DANGEROUS(8, 2, 2),
    FORMIDABLE(4, 3, 3),
    EXTREME(2, 4, 4),
    EPIC(1, 5, 5),
}

/** What a progress track measures. Bonds have no rank and advance one tick at a time. */
@Serializable
enum class ProgressTrackType(val isRanked: Boolean) {
    @SerialName("vow") VOW(true),
    @SerialName("journey") JOURNEY(true),
    @SerialName("combat") COMBAT(true),
    @SerialName("bonds") BONDS(false),
}

/**
 * A progress track (RF-MOT-07): ten boxes of four ticks each. Immutable: marking progress
 * returns a new track.
 */
@Serializable
data class ProgressTrack(
    val name: String,
    val type: ProgressTrackType,
    val rank: ChallengeRank?,
    val ticks: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "A progress track needs a name" }
        require(type.isRanked == (rank != null)) {
            if (type.isRanked) "$type tracks need a rank" else "$type tracks have no rank"
        }
        require(ticks in 0..MAX_TICKS) { "Ticks must be 0..$MAX_TICKS, was $ticks" }
    }

    /** Ticks added by each progress mark: the rank's value, or one for bonds. */
    val ticksPerProgress: Int get() = rank?.ticksPerProgress ?: 1

    /** Boxes with all four ticks: the score of a progress roll. */
    val filledBoxes: Int get() = ticks / TICKS_PER_BOX

    val isComplete: Boolean get() = ticks == MAX_TICKS

    /** Ticks in box [index] (0 to 9), from 0 to 4, for drawing the track. */
    fun ticksInBox(index: Int): Int {
        require(index in 0 until BOXES) { "Box must be 0..${BOXES - 1}, was $index" }
        return (ticks - index * TICKS_PER_BOX).coerceIn(0, TICKS_PER_BOX)
    }

    /** Marks progress [times] times, stopping when the track is full. */
    fun markProgress(times: Int = 1): ProgressTrack {
        require(times >= 0) { "Times cannot be negative, was $times" }
        return copy(ticks = minOf(ticks + times * ticksPerProgress, MAX_TICKS))
    }

    /** Clears progress down to [keepBoxes] filled boxes; partial ticks are cleared too. Never adds progress. */
    fun clearProgress(keepBoxes: Int = 0): ProgressTrack {
        require(keepBoxes in 0..BOXES) { "Boxes to keep must be 0..$BOXES, was $keepBoxes" }
        return copy(ticks = minOf(ticks, keepBoxes * TICKS_PER_BOX))
    }

    /** Raises the rank by one; epic stays epic. Bonds have no rank. */
    fun raiseRank(): ProgressTrack {
        val current = checkNotNull(rank) { "$type tracks have no rank" }
        return copy(rank = ChallengeRank.entries.getOrElse(current.ordinal + 1) { current })
    }

    companion object {
        const val BOXES = 10
        const val TICKS_PER_BOX = 4
        const val MAX_TICKS = BOXES * TICKS_PER_BOX
    }
}
