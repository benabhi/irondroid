package com.irondroid.engine.roll

import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.dice.SeededDice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionRollTest {
    private fun roll(actionDie: Int, stat: Int, adds: Int, c1: Int, c2: Int) =
        ActionRoll(actionDie, stat, adds, c1, c2)

    @Test
    fun `score adds the action die, the stat and the adds`() {
        assertEquals(7, roll(3, 2, 2, 1, 1).actionScore)
    }

    @Test
    fun `score is capped at 10`() {
        assertEquals(10, roll(6, 3, 4, 1, 1).actionScore)
        assertEquals(Outcome.WEAK_HIT, roll(6, 3, 4, 10, 9).outcome) // 10 ties the 10, beats the 9.
    }

    @Test
    fun `beating both challenge dice is a strong hit`() {
        assertEquals(Outcome.STRONG_HIT, roll(4, 2, 0, 3, 5).outcome)
    }

    @Test
    fun `beating one challenge die is a weak hit`() {
        assertEquals(Outcome.WEAK_HIT, roll(4, 2, 0, 3, 8).outcome)
        assertEquals(Outcome.WEAK_HIT, roll(4, 2, 0, 8, 3).outcome)
    }

    @Test
    fun `beating no challenge die is a miss`() {
        assertEquals(Outcome.MISS, roll(1, 1, 0, 5, 9).outcome)
    }

    @Test
    fun `ties go to the challenge dice`() {
        assertEquals(Outcome.MISS, roll(3, 2, 0, 5, 5).outcome)
        assertEquals(Outcome.WEAK_HIT, roll(3, 2, 0, 5, 4).outcome)
        assertEquals(Outcome.MISS, roll(6, 3, 1, 10, 10).outcome) // Capped 10 never beats a 10.
    }

    @Test
    fun `matching challenge dice are flagged whatever the outcome`() {
        assertTrue(roll(6, 3, 0, 2, 2).isMatch)
        assertEquals(Outcome.STRONG_HIT, roll(6, 3, 0, 2, 2).outcome)
        assertTrue(roll(1, 1, 0, 9, 9).isMatch)
        assertFalse(roll(1, 1, 0, 9, 8).isMatch)
    }

    @Test
    fun `rolling takes the d6 and then both d10 from the dice`() {
        val dice = FixedDice(5, 4, 9)
        assertEquals(ActionRoll(5, 2, 1, 4, 9), ActionRoll.roll(dice, stat = 2, adds = 1))
        assertEquals(0, dice.remaining)
    }

    @Test
    fun `rolling with seeded dice is reproducible`() {
        val first = List(100) { ActionRoll.roll(SeededDice.fromSeed(5), stat = 2) }
        val second = List(100) { ActionRoll.roll(SeededDice.fromSeed(5), stat = 2) }
        assertEquals(first, second)
    }

    @Test
    fun `invalid values are rejected`() {
        assertFailsWith<IllegalArgumentException> { roll(0, 1, 0, 1, 1) }
        assertFailsWith<IllegalArgumentException> { roll(1, -1, 0, 1, 1) }
        assertFailsWith<IllegalArgumentException> { roll(1, 1, -1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { roll(1, 1, 0, 11, 1) }
    }

    @Test
    fun `outcome rates match the expected odds for stat 2`() {
        // Exhaustive count over all 600 dice combinations with stat 2 and no adds.
        val counts = Outcome.entries.associateWith { 0 }.toMutableMap()
        for (d6 in 1..6) for (c1 in 1..10) for (c2 in 1..10) {
            counts.merge(roll(d6, 2, 0, c1, c2).outcome, 1, Int::plus)
        }
        assertEquals(mapOf(Outcome.STRONG_HIT to 139, Outcome.WEAK_HIT to 262, Outcome.MISS to 199), counts)
    }
}
