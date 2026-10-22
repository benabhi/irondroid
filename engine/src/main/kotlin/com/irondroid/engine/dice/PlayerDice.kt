package com.irondroid.engine.dice

/**
 * The game's dice with the player's own physical dice on top (RF-DAD-07): values loaded with [load]
 * are used by the next rolls of their die, in order; once they run out, or for dice not loaded,
 * [dice] rolls as usual, so a rule that asks for one more die never waits for the player.
 */
class PlayerDice(private val dice: Dice) : Dice {
    private val loaded = mutableMapOf<Die, ArrayDeque<Int>>()

    /** Loaded values not used yet. */
    val pending: Int @Synchronized get() = loaded.values.sumOf { it.size }

    /**
     * Uses [values] for the next rolls of [die], after any loaded before. Every value must be a face
     * of [die], or nothing is loaded: the engine checks what the player typed.
     */
    @Synchronized
    fun load(die: Die, values: List<Int>) {
        values.forEach { require(die.accepts(it)) { "$die cannot show $it (faces 1..${die.sides})" } }
        loaded.getOrPut(die) { ArrayDeque() }.addAll(values)
    }

    /** Drops the values not used, such as after an action that failed before rolling. */
    @Synchronized
    fun clear() {
        loaded.clear()
    }

    @Synchronized
    override fun roll(die: Die): DieRoll =
        loaded[die]?.removeFirstOrNull()?.let { DieRoll(die, it) } ?: dice.roll(die)
}
