package com.irondroid.engine.roll

import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die

/** Result of a roll against the challenge dice. */
enum class Outcome {
    /** The score beats both challenge dice. */
    STRONG_HIT,

    /** The score beats one challenge die. */
    WEAK_HIT,

    /** The score beats neither challenge die. */
    MISS;

    companion object {
        /** Outcome of [score] against two challenge dice. A tie goes to the challenge die. */
        fun of(score: Int, challenge1: Int, challenge2: Int): Outcome =
            when ((if (score > challenge1) 1 else 0) + (if (score > challenge2) 1 else 0)) {
                2 -> STRONG_HIT
                1 -> WEAK_HIT
                else -> MISS
            }
    }
}

/**
 * An action roll (RF-MOT-01, RF-MOT-02): one d6 plus a stat and adds, capped at 10,
 * against two d10 challenge dice.
 */
data class ActionRoll(
    val actionDie: Int,
    val stat: Int,
    val adds: Int,
    val challenge1: Int,
    val challenge2: Int,
) {
    init {
        require(Die.D6.accepts(actionDie)) { "Action die must be 1..6, was $actionDie" }
        require(stat >= 0) { "Stat cannot be negative, was $stat" }
        require(adds >= 0) { "Adds cannot be negative, was $adds" }
        require(Die.D10.accepts(challenge1) && Die.D10.accepts(challenge2)) {
            "Challenge dice must be 1..10, were $challenge1 and $challenge2"
        }
    }

    /** Action die + stat + adds, never above [MAX_ACTION_SCORE]. */
    val actionScore: Int get() = minOf(actionDie + stat + adds, MAX_ACTION_SCORE)

    val outcome: Outcome get() = Outcome.of(actionScore, challenge1, challenge2)

    /** Both challenge dice show the same value: a twist or an opportunity, whatever the outcome. */
    val isMatch: Boolean get() = challenge1 == challenge2

    companion object {
        const val MAX_ACTION_SCORE = 10

        /** Rolls the action die and both challenge dice, in that order, from [dice]. */
        fun roll(dice: Dice, stat: Int, adds: Int = 0): ActionRoll {
            val (action, first, second) = dice.roll(Die.D6, Die.D10, Die.D10).dice.map { it.value }
            return ActionRoll(action, stat, adds, first, second)
        }
    }
}
