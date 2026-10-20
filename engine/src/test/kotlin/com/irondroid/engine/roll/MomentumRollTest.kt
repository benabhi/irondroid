package com.irondroid.engine.roll

import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.momentum.Momentum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How momentum changes an action roll: burning (RF-MOT-03) and negative momentum (RF-MOT-04). */
class MomentumRollTest {
    @Test
    fun `negative momentum matching the action die cancels it`() {
        val roll = ActionRoll(actionDie = 3, stat = 2, adds = 1, challenge1 = 4, challenge2 = 6, momentum = -3)
        assertTrue(roll.isActionDieCancelled)
        assertEquals(3, roll.actionScore) // Only stat + adds.
        assertEquals(Outcome.MISS, roll.outcome)
    }

    @Test
    fun `negative momentum that does not match leaves the die alone`() {
        val roll = ActionRoll(3, 2, 1, 4, 6, momentum = -4)
        assertFalse(roll.isActionDieCancelled)
        assertEquals(6, roll.actionScore)
        assertEquals(Outcome.WEAK_HIT, roll.outcome)
    }

    @Test
    fun `positive momentum never cancels the die`() {
        assertFalse(ActionRoll(3, 2, 0, 1, 1, momentum = 3).isActionDieCancelled)
    }

    @Test
    fun `burning is offered when it improves the outcome`() {
        val roll = ActionRoll(2, 1, 0, 5, 8, momentum = 9) // Score 3: miss.
        assertEquals(Outcome.STRONG_HIT, roll.burnOutcome(Momentum(9)))
        assertEquals(Outcome.WEAK_HIT, roll.burnOutcome(Momentum(6)))
    }

    @Test
    fun `burning is not offered when it does not help`() {
        val roll = ActionRoll(4, 2, 0, 5, 8) // Score 6: weak hit.
        assertNull(roll.burnOutcome(Momentum(7))) // 7 also beats only the 5.
        assertNull(roll.burnOutcome(Momentum(5))) // Ties do not cancel a die.
        assertNull(roll.burnOutcome(Momentum(0)))
        assertNull(ActionRoll(1, 1, 0, 1, 1).burnOutcome(Momentum(-2)))
        assertNull(ActionRoll(6, 3, 0, 2, 4).burnOutcome(Momentum(10))) // Already a strong hit.
    }

    @Test
    fun `burning cancels the lower dice and resets momentum`() {
        val roll = ActionRoll(2, 1, 0, 5, 8, momentum = 9)
        val result = roll.burn(Momentum(9, debilities = 1))
        assertEquals(Outcome.STRONG_HIT, result.roll.outcome)
        assertEquals(9, result.roll.burnedMomentum)
        assertEquals(Momentum(1, debilities = 1), result.momentum)
        assertNull(result.roll.burnOutcome(Momentum(9))) // Only once per roll.
    }

    @Test
    fun `burning keeps the match`() {
        val result = ActionRoll(1, 1, 0, 4, 4).burn(Momentum(7))
        assertEquals(Outcome.STRONG_HIT, result.roll.outcome)
        assertTrue(result.roll.isMatch)
    }

    @Test
    fun `burning when it does not help fails`() {
        assertFailsWith<IllegalStateException> { ActionRoll(4, 2, 0, 5, 8).burn(Momentum(7)) }
    }

    @Test
    fun `rolling records the momentum of the moment`() {
        val roll = ActionRoll.roll(FixedDice(2, 3, 9), stat = 3, momentum = -2)
        assertTrue(roll.isActionDieCancelled)
        assertEquals(3, roll.actionScore)
        assertEquals(Outcome.MISS, roll.outcome)
    }
}
