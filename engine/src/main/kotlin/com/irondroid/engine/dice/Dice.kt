package com.irondroid.engine.dice

/**
 * Source of dice results for the whole engine. Rules never use a random generator directly,
 * so a game is reproducible from its dice source (RF-MOT-12).
 */
interface Dice {
    /** Rolls one [die] and returns its result. */
    fun roll(die: Die): DieRoll

    /** Rolls [dice] in order and groups them as one roll. */
    fun roll(vararg dice: Die): DiceRoll = DiceRoll(dice.map { roll(it) })
}

/**
 * Deterministic dice: the same seed always gives the same sequence.
 *
 * Uses SplitMix64 instead of [kotlin.random.Random] because its whole state is one [Long]:
 * a saved game stores [state] and resumes the exact sequence with [SeededDice.fromState].
 */
class SeededDice private constructor(state: Long) : Dice {
    /** Current generator state. Save it to continue the same sequence later. */
    var state: Long = state
        private set

    override fun roll(die: Die): DieRoll = DieRoll(die, nextInt(die.sides) + 1)

    /** Uniform value in 0 until [bound], using rejection to avoid modulo bias. */
    private fun nextInt(bound: Int): Int {
        val range = bound.toULong()
        val limit = ULong.MAX_VALUE - (ULong.MAX_VALUE % range) // Values at or above this are rejected.
        while (true) {
            val candidate = nextLong().toULong()
            if (candidate < limit) return (candidate % range).toInt()
        }
    }

    private fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    companion object {
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        private const val MIX_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        private const val MIX_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB

        /** New dice for a game started with [seed]. */
        fun fromSeed(seed: Long): SeededDice = SeededDice(seed)

        /** Dice that continue from a [state] saved earlier. */
        fun fromState(state: Long): SeededDice = SeededDice(state)
    }
}

/**
 * Dice that return values decided in advance, in order. For tests and for replaying a known roll.
 * Fails if a value does not fit the die asked for or if the values run out.
 */
class FixedDice(values: List<Int>) : Dice {
    constructor(vararg values: Int) : this(values.toList())

    private val queue = ArrayDeque(values)

    /** Values not used yet. */
    val remaining: Int get() = queue.size

    override fun roll(die: Die): DieRoll {
        val value = checkNotNull(queue.removeFirstOrNull()) { "No fixed value left for $die" }
        return DieRoll(die, value)
    }
}
