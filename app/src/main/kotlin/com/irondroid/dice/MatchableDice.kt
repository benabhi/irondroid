package com.irondroid.dice

import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import com.irondroid.engine.dice.DieRoll

/**
 * The game's dice, with a development switch (IRON-55): after [forceMatch], the next two d10 rolls,
 * the challenge dice of an action roll, show the same number. Otherwise it rolls [dice] as it is.
 */
class MatchableDice(private val dice: Dice) : Dice {
    @Volatile
    var matchPending: Boolean = false
        private set

    private var first: Int? = null

    fun forceMatch() {
        matchPending = true
        first = null
    }

    @Synchronized
    override fun roll(die: Die): DieRoll {
        val roll = dice.roll(die)
        if (!matchPending || die != Die.D10) return roll
        val value = first ?: run {
            first = roll.value
            return roll
        }
        matchPending = false
        first = null
        return DieRoll(die, value)
    }
}
