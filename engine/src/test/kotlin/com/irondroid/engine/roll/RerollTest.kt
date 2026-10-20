package com.irondroid.engine.roll

import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.momentum.Momentum
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.move.MoveRequest
import com.irondroid.engine.move.MoveResolver
import com.irondroid.engine.move.MoveStep
import com.irondroid.engine.move.RollRecord
import com.irondroid.engine.move.RollSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Rerolling dice when an asset allows it (RF-MOT-11, IRON-27). */
class RerollTest {
    private val roll = ActionRoll(actionDie = 2, stat = 2, adds = 0, challenge1 = 5, challenge2 = 9) // 4: a miss.

    @Test
    fun `only the chosen dice are rolled again`() {
        assertEquals(ActionRoll(6, 2, 0, 5, 9), roll.reroll(FixedDice(6), setOf(RerollDie.ACTION)))
        assertEquals(ActionRoll(2, 2, 0, 5, 1), roll.reroll(FixedDice(1), setOf(RerollDie.CHALLENGE_2)))
        assertEquals(ActionRoll(2, 2, 0, 3, 4), roll.reroll(FixedDice(3, 4), setOf(RerollDie.CHALLENGE_1, RerollDie.CHALLENGE_2)))
        assertEquals(roll, roll.reroll(FixedDice(), emptySet()))
    }

    @Test
    fun `the new dice decide the outcome`() {
        assertEquals(Outcome.MISS, roll.outcome)
        assertEquals(Outcome.WEAK_HIT, roll.reroll(FixedDice(6), setOf(RerollDie.ACTION)).outcome) // 8: beats 5.
    }

    @Test
    fun `no reroll after burning momentum`() {
        val burned = roll.burn(Momentum(10)).roll
        assertFailsWith<IllegalStateException> { burned.reroll(FixedDice(6), setOf(RerollDie.ACTION)) }
    }

    private val catalog = MoveLoader.fromResources()
    private val hero = Character.create(edge = 2, heart = 1, iron = 3, shadow = 2, wits = 1)

    @Test
    fun `a move with a reroll waits before applying anything`() {
        val request = MoveRequest(source = RollSource.EDGE, rerollable = setOf(RerollDie.ACTION))
        val resolver = MoveResolver(catalog, FixedDice(2, 5, 9, 6)) // First 2 + 2 = 4, then the d6 comes up 6.
        val step = assertIs<MoveStep.AwaitingReroll>(resolver.make(hero, "face_danger", request))
        assertEquals(hero, step.character)

        val kept = resolver.reroll(step, emptySet())
        assertEquals(Outcome.MISS, kept.record.roll?.outcome)

        val rerolled = assertIs<MoveStep.AwaitingChoice>(resolver.reroll(step, setOf(RerollDie.ACTION)))
        assertEquals(6, assertIs<RollRecord.Action>(rerolled.record.roll).roll.actionDie)
        assertEquals(Outcome.WEAK_HIT, rerolled.record.roll.outcome)
    }

    @Test
    fun `only the dice the asset allows can be rerolled`() {
        val request = MoveRequest(source = RollSource.EDGE, rerollable = setOf(RerollDie.ACTION))
        val resolver = MoveResolver(catalog, FixedDice(2, 5, 9, 1))
        val step = assertIs<MoveStep.AwaitingReroll>(resolver.make(hero, "face_danger", request))
        assertFailsWith<IllegalArgumentException> { resolver.reroll(step, setOf(RerollDie.CHALLENGE_1)) }
    }

    @Test
    fun `burning momentum is still offered after a reroll`() {
        val request = MoveRequest(source = RollSource.EDGE, rerollable = setOf(RerollDie.CHALLENGE_1))
        val strong = hero.copy(momentum = Momentum(10))
        val resolver = MoveResolver(catalog, FixedDice(1, 9, 9, 8)) // 3 against 9 and 9; the first d10 rerolls to 8.
        val step = assertIs<MoveStep.AwaitingReroll>(resolver.make(strong, "face_danger", request))
        val burn = assertIs<MoveStep.AwaitingBurn>(resolver.reroll(step, setOf(RerollDie.CHALLENGE_1)))
        assertEquals(Outcome.STRONG_HIT, burn.burnOutcome)
    }

    @Test
    fun `moves without a reroll do not stop`() {
        val step = MoveResolver(catalog, FixedDice(5, 3, 4)).make(hero, "face_danger", MoveRequest(source = RollSource.EDGE))
        assertIs<MoveStep.Done>(step)
    }
}
