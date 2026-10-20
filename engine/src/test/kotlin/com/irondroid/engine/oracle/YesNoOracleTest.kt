package com.irondroid.engine.oracle

import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.dice.SeededDice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YesNoOracleTest {
    @Test
    fun `each odds says yes from its threshold in the SRD table`() {
        val expected = mapOf(
            Odds.ALMOST_CERTAIN to 11,
            Odds.LIKELY to 26,
            Odds.FIFTY_FIFTY to 51,
            Odds.UNLIKELY to 76,
            Odds.SMALL_CHANCE to 91,
        )
        assertEquals(expected, Odds.entries.associateWith { it.yesFrom })
    }

    @Test
    fun `answer is no just below the threshold and yes on it`() {
        for (odds in Odds.entries) {
            assertFalse(OracleAnswer(odds, odds.yesFrom - 1).isYes, "$odds at ${odds.yesFrom - 1}")
            assertTrue(OracleAnswer(odds, odds.yesFrom).isYes, "$odds at ${odds.yesFrom}")
        }
    }

    @Test
    fun `lowest roll is always no and highest is always yes`() {
        for (odds in Odds.entries) {
            assertFalse(OracleAnswer(odds, 1).isYes)
            assertTrue(OracleAnswer(odds, 100).isYes)
        }
    }

    @Test
    fun `matches are extreme, including 100 read as 00`() {
        val extremes = (1..100).filter { OracleAnswer(Odds.FIFTY_FIFTY, it).isExtreme }
        assertEquals(listOf(11, 22, 33, 44, 55, 66, 77, 88, 99, 100), extremes)
    }

    @Test
    fun `extreme answers can be yes or no`() {
        val extremeNo = OracleAnswer(Odds.LIKELY, 22)
        assertTrue(extremeNo.isExtreme)
        assertFalse(extremeNo.isYes)

        val extremeYes = OracleAnswer(Odds.LIKELY, 77)
        assertTrue(extremeYes.isExtreme)
        assertTrue(extremeYes.isYes)
    }

    @Test
    fun `ask rolls one d100 from the dice`() {
        val dice = FixedDice(51, 50)
        assertEquals(OracleAnswer(Odds.FIFTY_FIFTY, 51), YesNoOracle.ask(dice, Odds.FIFTY_FIFTY))
        assertFalse(YesNoOracle.ask(dice, Odds.FIFTY_FIFTY).isYes)
        assertEquals(0, dice.remaining)
    }

    @Test
    fun `same seed gives the same answers`() {
        val first = SeededDice.fromSeed(2026)
        val second = SeededDice.fromSeed(2026)
        repeat(100) {
            val odds = Odds.entries[it % Odds.entries.size]
            assertEquals(YesNoOracle.ask(first, odds), YesNoOracle.ask(second, odds))
        }
    }

    @Test
    fun `yes comes out about as often as the odds say`() {
        val dice = SeededDice.fromSeed(7)
        val asks = 100_000
        for (odds in Odds.entries) {
            val yes = (1..asks).count { YesNoOracle.ask(dice, odds).isYes }
            val expected = (101 - odds.yesFrom) / 100.0
            assertEquals(expected, yes.toDouble() / asks, 0.01, "$odds")
        }
    }

    @Test
    fun `roll outside the d100 is rejected`() {
        assertFailsWith<IllegalArgumentException> { OracleAnswer(Odds.LIKELY, 0) }
        assertFailsWith<IllegalArgumentException> { OracleAnswer(Odds.LIKELY, 101) }
    }
}
