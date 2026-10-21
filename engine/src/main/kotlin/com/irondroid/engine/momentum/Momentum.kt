package com.irondroid.engine.momentum

import kotlinx.serialization.Serializable

/**
 * The character's momentum (RF-MOT-03 to RF-MOT-05). Immutable: every change returns a new value.
 *
 * Ranges from [MIN] to [max]; each marked debility lowers the maximum by one and the reset value
 * (+2, +1 with one debility, 0 with two or more).
 */
@Serializable
data class Momentum(val value: Int = START, val debilities: Int = 0) {
    init {
        require(debilities in 0..MAX_DEBILITIES) { "Debilities must be 0..$MAX_DEBILITIES, was $debilities" }
        require(value in MIN..max) { "Momentum must be $MIN..$max, was $value" }
    }

    /** Highest momentum allowed with the current debilities. */
    val max: Int get() = maxFor(debilities)

    /** Value momentum returns to after burning it. */
    val reset: Int get() = resetFor(debilities)

    /** Adds [amount], stopping at [max]. */
    fun gain(amount: Int): Momentum {
        require(amount >= 0) { "Gain cannot be negative, was $amount" }
        return copy(value = minOf(value + amount, max))
    }

    /**
     * Removes [amount], stopping at [MIN]. The part that does not fit below the minimum is reported
     * in [MomentumLoss.overflow]: when it is above zero the player must Face a Setback.
     */
    fun lose(amount: Int): MomentumLoss {
        require(amount >= 0) { "Loss cannot be negative, was $amount" }
        val target = value - amount
        return MomentumLoss(copy(value = maxOf(target, MIN)), overflow = maxOf(MIN - target, 0))
    }

    /** Back to the reset value, as after burning. */
    fun resetValue(): Momentum = copy(value = reset)

    /** Same momentum with a new debility count; the value drops if it is above the new maximum. */
    fun withDebilities(count: Int): Momentum = Momentum(minOf(value, maxFor(count)), count)

    companion object {
        const val MIN = -6
        const val MAX = 10
        const val START = 2

        /** Four conditions, two banes and two burdens. */
        const val MAX_DEBILITIES = 8

        fun maxFor(debilities: Int): Int = MAX - debilities

        fun resetFor(debilities: Int): Int = when (debilities) {
            0 -> 2
            1 -> 1
            else -> 0
        }
    }
}

/** Result of losing momentum. */
data class MomentumLoss(val momentum: Momentum, val overflow: Int) {
    /** The loss went past the minimum: the player must Face a Setback for [overflow]. */
    val requiresSetback: Boolean get() = overflow > 0
}
