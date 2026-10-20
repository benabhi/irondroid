package com.irondroid.engine.dice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiceTest {
    private val actionRoll = arrayOf(Die.D6, Die.D10, Die.D10)

    @Test
    fun `same seed gives the same rolls`() {
        val first = SeededDice.fromSeed(1234)
        val second = SeededDice.fromSeed(1234)
        repeat(1_000) { assertEquals(first.roll(*actionRoll), second.roll(*actionRoll)) }
    }

    @Test
    fun `different seeds give different rolls`() {
        val first = SeededDice.fromSeed(1)
        val second = SeededDice.fromSeed(2)
        val a = List(50) { first.roll(Die.D100).value }
        val b = List(50) { second.roll(Die.D100).value }
        assertNotEquals(a, b)
    }

    @Test
    fun `seed 42 keeps its known sequence so saved games stay compatible`() {
        // Computed independently from the SplitMix64 reference; changing it breaks old saves.
        val dice = SeededDice.fromSeed(42)
        val roll = dice.roll(Die.D6, Die.D10, Die.D10, Die.D100, Die.D6, Die.D10, Die.D10, Die.D100)
        assertEquals(listOf(2, 2, 9, 65, 5, 3, 6, 9), roll.dice.map { it.value })
    }

    @Test
    fun `saved state resumes the same sequence`() {
        val original = SeededDice.fromSeed(42)
        repeat(3) { original.roll(Die.D6) }
        assertEquals(-2691343689449507735, original.state)

        val restored = SeededDice.fromState(original.state)
        repeat(100) { assertEquals(original.roll(Die.D10), restored.roll(Die.D10)) }
    }

    @Test
    fun `every die stays in range and shows every face`() {
        val dice = SeededDice.fromSeed(7)
        for (die in Die.entries) {
            val seen = List(die.sides * 200) { dice.roll(die).value }.toSet()
            assertEquals((1..die.sides).toSet(), seen, "$die faces")
        }
    }

    @Test
    fun `d10 faces come out evenly`() {
        val dice = SeededDice.fromSeed(99)
        val rolls = 100_000
        val counts = IntArray(10)
        repeat(rolls) { counts[dice.roll(Die.D10).value - 1]++ }
        val expected = rolls / 10
        counts.forEachIndexed { face, count ->
            assertTrue(count in (expected * 0.95).toInt()..(expected * 1.05).toInt(), "face ${face + 1}: $count")
        }
    }

    @Test
    fun `fixed dice return their values in order`() {
        val dice = FixedDice(4, 7, 10)
        val roll = dice.roll(*actionRoll)
        assertEquals(listOf(DieRoll(Die.D6, 4), DieRoll(Die.D10, 7), DieRoll(Die.D10, 10)), roll.dice)
        assertEquals(0, dice.remaining)
    }

    @Test
    fun `fixed dice reject values the die cannot show and running out`() {
        assertFailsWith<IllegalArgumentException> { FixedDice(7).roll(Die.D6) }
        assertFailsWith<IllegalStateException> { FixedDice().roll(Die.D6) }
    }

    @Test
    fun `a die roll only accepts valid faces`() {
        assertFailsWith<IllegalArgumentException> { DieRoll(Die.D10, 0) }
        assertFailsWith<IllegalArgumentException> { DieRoll(Die.D100, 101) }
        assertEquals(100, DieRoll(Die.D100, 100).value)
    }

    @Test
    fun `a roll groups its dice by type in order`() {
        val roll = FixedDice(3, 8, 2).roll(*actionRoll)
        assertEquals(listOf(3), roll.valuesOf(Die.D6))
        assertEquals(listOf(8, 2), roll.valuesOf(Die.D10))
        assertFailsWith<IllegalArgumentException> { DiceRoll(emptyList()) }
    }
}
