package com.irondroid.engine.roll

import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import com.irondroid.engine.progress.ProgressTrack

/**
 * A progress roll (RF-MOT-06): the filled boxes of a track against two d10 challenge dice.
 * There is no action die, and momentum is neither burned nor applied.
 */
data class ProgressRoll(
    val progressScore: Int,
    val challenge1: Int,
    val challenge2: Int,
) {
    init {
        require(progressScore in 0..ProgressTrack.BOXES) {
            "Progress score must be 0..${ProgressTrack.BOXES}, was $progressScore"
        }
        require(Die.D10.accepts(challenge1) && Die.D10.accepts(challenge2)) {
            "Challenge dice must be 1..10, were $challenge1 and $challenge2"
        }
    }

    val outcome: Outcome get() = Outcome.of(progressScore, challenge1, challenge2)

    /** Both challenge dice show the same value. */
    val isMatch: Boolean get() = challenge1 == challenge2

    companion object {
        /** Rolls both challenge dice from [dice] against the filled boxes of [track]. */
        fun roll(dice: Dice, track: ProgressTrack): ProgressRoll {
            val (first, second) = dice.roll(Die.D10, Die.D10).dice.map { it.value }
            return ProgressRoll(track.filledBoxes, first, second)
        }
    }
}
