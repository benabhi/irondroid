package com.irondroid.engine.dice

import com.irondroid.engine.roll.ActionRoll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerDiceTest {
    private val fallback = FixedDice(3, 9, 1, 77)

    @Test
    fun `loaded values are rolled in order, each by its die`() {
        val dice = PlayerDice(fallback)
        dice.load(Die.D10, listOf(10, 2))
        dice.load(Die.D6, listOf(6))
        assertEquals(3, dice.pending)
        assertEquals(listOf(6, 10, 2), dice.roll(Die.D6, Die.D10, Die.D10).dice.map { it.value })
        assertEquals(0, dice.pending)
        assertEquals(4, fallback.remaining)
    }

    @Test
    fun `without loaded values, or once they run out, the game's dice roll`() {
        val dice = PlayerDice(fallback)
        dice.load(Die.D100, listOf(100))
        assertEquals(100, dice.roll(Die.D100).value)
        assertEquals(3, dice.roll(Die.D6).value)
        assertEquals(9, dice.roll(Die.D100).value)
    }

    @Test
    fun `a value the die cannot show is refused and nothing is loaded`() {
        val dice = PlayerDice(fallback)
        assertFailsWith<IllegalArgumentException> { dice.load(Die.D6, listOf(4, 7)) }
        assertFailsWith<IllegalArgumentException> { dice.load(Die.D10, listOf(0)) }
        assertEquals(0, dice.pending)
    }

    @Test
    fun `clearing drops the values not used`() {
        val dice = PlayerDice(fallback)
        dice.load(Die.D6, listOf(5))
        dice.clear()
        assertEquals(0, dice.pending)
        assertEquals(3, dice.roll(Die.D6).value)
    }

    @Test
    fun `an action roll uses the player's dice`() {
        val dice = PlayerDice(fallback)
        dice.load(Die.D6, listOf(4))
        dice.load(Die.D10, listOf(2, 8))
        val roll = ActionRoll.roll(dice, stat = 2)
        assertEquals(listOf(4, 2, 8), listOf(roll.actionDie, roll.challenge1, roll.challenge2))
    }
}
