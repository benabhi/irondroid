package com.irondroid.engine.roll

import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import com.irondroid.engine.momentum.Momentum

/** Result of a roll against the challenge dice. */
enum class Outcome {
    /** The score beats both challenge dice. */
    STRONG_HIT,

    /** The score beats one challenge die. */
    WEAK_HIT,

    /** The score beats neither challenge die. */
    MISS;

    /** Whether this outcome is better for the player than [other]. */
    fun isBetterThan(other: Outcome): Boolean = ordinal < other.ordinal

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
 * An action roll (RF-MOT-01 to RF-MOT-04): one d6 plus a stat and adds, capped at 10,
 * against two d10 challenge dice.
 *
 * [momentum] is the momentum when the roll was made: when negative and equal to the action die,
 * the die counts as 0. [burnedMomentum] is set once the player burns momentum on this roll.
 */
data class ActionRoll(
    val actionDie: Int,
    val stat: Int,
    val adds: Int,
    val challenge1: Int,
    val challenge2: Int,
    val momentum: Int = 0,
    val burnedMomentum: Int? = null,
) {
    init {
        require(Die.D6.accepts(actionDie)) { "Action die must be 1..6, was $actionDie" }
        require(stat >= 0) { "Stat cannot be negative, was $stat" }
        require(adds >= 0) { "Adds cannot be negative, was $adds" }
        require(Die.D10.accepts(challenge1) && Die.D10.accepts(challenge2)) {
            "Challenge dice must be 1..10, were $challenge1 and $challenge2"
        }
        require(momentum in Momentum.MIN..Momentum.MAX) { "Momentum must be ${Momentum.MIN}..${Momentum.MAX}, was $momentum" }
    }

    /** Negative momentum matches the action die, so the die counts as 0 (RF-MOT-04). */
    val isActionDieCancelled: Boolean get() = momentum < 0 && -momentum == actionDie

    /** Action die + stat + adds, never above [MAX_ACTION_SCORE]. */
    val actionScore: Int
        get() = minOf((if (isActionDieCancelled) 0 else actionDie) + stat + adds, MAX_ACTION_SCORE)

    /** Final outcome, counting burned momentum if the player burned it. */
    val outcome: Outcome get() = Outcome.of(burnedMomentum ?: actionScore, challenge1, challenge2)

    /** Both challenge dice show the same value: a twist or an opportunity, whatever the outcome. */
    val isMatch: Boolean get() = challenge1 == challenge2

    /**
     * Outcome if the player burned [momentum] now, or null when burning would not improve it (RF-MOT-03).
     * Burning cancels the challenge dice below the momentum, so it only helps with positive momentum.
     */
    fun burnOutcome(momentum: Momentum): Outcome? {
        if (burnedMomentum != null || momentum.value <= 0) return null
        val burned = Outcome.of(momentum.value, challenge1, challenge2)
        return burned.takeIf { it.isBetterThan(outcome) }
    }

    /** Burns [momentum]: returns the improved roll and the momentum back at its reset value. */
    fun burn(momentum: Momentum): BurnResult {
        checkNotNull(burnOutcome(momentum)) { "Burning momentum ${momentum.value} does not improve this roll" }
        return BurnResult(copy(burnedMomentum = momentum.value), momentum.resetValue())
    }

    companion object {
        const val MAX_ACTION_SCORE = 10

        /** Rolls the action die and both challenge dice, in that order, from [dice]. */
        fun roll(dice: Dice, stat: Int, adds: Int = 0, momentum: Int = 0): ActionRoll {
            val (action, first, second) = dice.roll(Die.D6, Die.D10, Die.D10).dice.map { it.value }
            return ActionRoll(action, stat, adds, first, second, momentum)
        }
    }
}

/** An action roll after burning momentum, with the momentum that is left. */
data class BurnResult(val roll: ActionRoll, val momentum: Momentum)
