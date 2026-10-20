package com.irondroid.engine.roll

import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrack
import com.irondroid.engine.progress.ProgressTrackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProgressRollTest {
    private val track = ProgressTrack("Reach the Iron Hills", ProgressTrackType.JOURNEY, ChallengeRank.DANGEROUS, ticks = 22)

    @Test
    fun `the score is the filled boxes and only the two d10 are rolled`() {
        val dice = FixedDice(3, 7)
        val roll = ProgressRoll.roll(dice, track)
        assertEquals(ProgressRoll(5, 3, 7), roll) // 22 ticks = 5 full boxes.
        assertEquals(0, dice.remaining)
    }

    @Test
    fun `outcomes follow the challenge dice with ties to the challenge`() {
        assertEquals(Outcome.STRONG_HIT, ProgressRoll(5, 3, 4).outcome)
        assertEquals(Outcome.WEAK_HIT, ProgressRoll(5, 5, 4).outcome)
        assertEquals(Outcome.MISS, ProgressRoll(5, 5, 9).outcome)
    }

    @Test
    fun `a full track beats anything but a 10`() {
        assertEquals(Outcome.STRONG_HIT, ProgressRoll(10, 9, 9).outcome)
        assertEquals(Outcome.WEAK_HIT, ProgressRoll(10, 10, 1).outcome)
        assertEquals(Outcome.MISS, ProgressRoll(10, 10, 10).outcome)
    }

    @Test
    fun `an empty track always misses`() {
        assertEquals(Outcome.MISS, ProgressRoll(0, 1, 1).outcome)
    }

    @Test
    fun `matches are flagged`() {
        assertTrue(ProgressRoll(10, 10, 10).isMatch)
    }

    @Test
    fun `invalid values are rejected`() {
        assertFailsWith<IllegalArgumentException> { ProgressRoll(11, 1, 1) }
        assertFailsWith<IllegalArgumentException> { ProgressRoll(5, 0, 1) }
    }
}
