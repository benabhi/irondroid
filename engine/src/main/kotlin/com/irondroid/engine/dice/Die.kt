package com.irondroid.engine.dice

/** The dice Ironsworn uses: d6 for action rolls, d10 for challenge dice and d100 for oracles. */
enum class Die(val sides: Int) {
    D6(6),
    D10(10),
    D100(100);

    /** Whether [value] is a face this die can show. */
    fun accepts(value: Int): Boolean = value in 1..sides
}

/** One die after it was rolled. The value is always a valid face of [die]. */
data class DieRoll(val die: Die, val value: Int) {
    init {
        require(die.accepts(value)) { "$die cannot show $value (faces 1..${die.sides})" }
    }
}

/** All the dice of a single roll, in the order they were rolled. */
data class DiceRoll(val dice: List<DieRoll>) {
    init {
        require(dice.isNotEmpty()) { "A roll needs at least one die" }
    }

    /** Values of the dice of type [die], in roll order. */
    fun valuesOf(die: Die): List<Int> = dice.filter { it.die == die }.map { it.value }
}
