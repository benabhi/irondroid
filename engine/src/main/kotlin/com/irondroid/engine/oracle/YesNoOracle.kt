package com.irondroid.engine.oracle

import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die

/** How likely a "yes" is when asking the yes/no oracle (RF-ORA-01). The answer is yes on [yesFrom] or more. */
enum class Odds(val yesFrom: Int) {
    ALMOST_CERTAIN(11),
    LIKELY(26),
    FIFTY_FIFTY(51),
    UNLIKELY(76),
    SMALL_CHANCE(91),
}

/**
 * The answer of the yes/no oracle: a d100 [roll] read against [odds].
 *
 * A match (both digits equal: 11, 22 ... 99, and 100 read as 00) is an extreme result or a twist.
 */
data class OracleAnswer(val odds: Odds, val roll: Int) {
    init {
        require(Die.D100.accepts(roll)) { "Oracle roll must be 1..100, was $roll" }
    }

    /** Whether the answer is yes. */
    val isYes: Boolean get() = roll >= odds.yesFrom

    /** Whether the roll is a match, so the answer is extreme or brings a twist. */
    val isExtreme: Boolean get() = roll % 10 == (roll / 10) % 10
}

/** Asks the yes/no oracle ("Ask the Oracle", RF-ORA-01). */
object YesNoOracle {
    /** Rolls 1d100 from [dice] and reads it against [odds]. */
    fun ask(dice: Dice, odds: Odds): OracleAnswer = OracleAnswer(odds, dice.roll(Die.D100).value)
}
