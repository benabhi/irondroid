package com.irondroid.engine

import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.meters.Meter
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.momentum.Momentum
import com.irondroid.engine.move.Effect
import com.irondroid.engine.move.MoveCatalog
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.move.MoveRequest
import com.irondroid.engine.move.MoveResolver
import com.irondroid.engine.move.MoveRoll
import com.irondroid.engine.move.MoveStep
import com.irondroid.engine.move.NewTrack
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrack
import com.irondroid.engine.progress.ProgressTrackType
import com.irondroid.engine.roll.ActionRoll
import com.irondroid.engine.roll.Outcome
import com.irondroid.engine.roll.ProgressRoll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thousands of random rolls and games with seeded dice (IRON-28). Each test checks that the rules
 * never break, whatever the dice say. Seeds are fixed, so a failure always reproduces.
 */
class InvariantsTest {
    private val dice = SeededDice.fromSeed(2026)
    private val random = Random(2026)

    @Test
    fun `action rolls always follow the rules`() {
        repeat(20_000) {
            val momentum = Momentum(random.nextInt(Momentum.MIN, Momentum.MAX + 1))
            val roll = ActionRoll.roll(dice, stat = random.nextInt(0, 5), adds = random.nextInt(0, 4), momentum = momentum.value)
            val raw = (if (roll.isActionDieCancelled) 0 else roll.actionDie) + roll.stat + roll.adds
            assertEquals(minOf(raw, 10), roll.actionScore, "$roll")
            assertEquals(roll.isActionDieCancelled, momentum.value < 0 && -momentum.value == roll.actionDie, "$roll")
            val beaten = listOf(roll.challenge1, roll.challenge2).count { roll.actionScore > it }
            assertEquals(listOf(Outcome.MISS, Outcome.WEAK_HIT, Outcome.STRONG_HIT)[beaten], roll.outcome, "$roll")

            val burn = roll.burnOutcome(momentum)
            if (burn != null) {
                assertTrue(momentum.value > 0 && burn.isBetterThan(roll.outcome), "$roll burning $momentum")
                val burned = roll.burn(momentum)
                assertEquals(burn, burned.roll.outcome)
                assertEquals(momentum.reset, burned.momentum.value)
            } else {
                val wouldBe = Outcome.of(momentum.value, roll.challenge1, roll.challenge2)
                assertTrue(momentum.value <= 0 || !wouldBe.isBetterThan(roll.outcome), "$roll should offer burning $momentum")
            }
        }
    }

    @Test
    fun `progress rolls only count full boxes`() {
        repeat(5_000) {
            val track = ProgressTrack("Vow", ProgressTrackType.VOW, ChallengeRank.entries.random(random), random.nextInt(0, 41))
            val roll = ProgressRoll.roll(dice, track)
            assertEquals(track.ticks / 4, roll.progressScore)
            assertTrue(roll.progressScore in 0..10)
        }
    }

    @Test
    fun `momentum stays in range through any gains, losses and debilities`() {
        var momentum = Momentum()
        repeat(20_000) {
            momentum = when (random.nextInt(4)) {
                0 -> momentum.gain(random.nextInt(0, 6))
                1 -> {
                    val before = momentum.value
                    val amount = random.nextInt(0, 6)
                    val loss = momentum.lose(amount)
                    assertEquals(before - amount, loss.momentum.value - loss.overflow, "losing $amount from $before")
                    loss.momentum
                }
                2 -> momentum.withDebilities(random.nextInt(0, Momentum.MAX_DEBILITIES + 1))
                else -> momentum.resetValue()
            }
            assertTrue(momentum.value in Momentum.MIN..momentum.max, "$momentum")
            assertEquals(Momentum.MAX - momentum.debilities, momentum.max)
        }
    }

    @Test
    fun `tracks and meters stay in range`() {
        var track = ProgressTrack("Journey", ProgressTrackType.JOURNEY, ChallengeRank.TROUBLESOME)
        var meter = Meter(MeterType.HEALTH)
        repeat(20_000) {
            track = when (random.nextInt(4)) {
                0, 1 -> track.markProgress(random.nextInt(0, 3))
                2 -> track.clearProgress(random.nextInt(0, 11))
                else -> track.raiseRank()
            }
            assertTrue(track.ticks in 0..ProgressTrack.MAX_TICKS && track.filledBoxes in 0..ProgressTrack.BOXES, "$track")
            meter = if (random.nextBoolean()) meter.gain(random.nextInt(0, 4)) else meter.lose(random.nextInt(0, 4)).meter
            assertTrue(meter.value in Meter.MIN..Meter.MAX, "$meter")
        }
    }

    @Test
    fun `seeded dice give the same game twice`() {
        assertEquals(playGame(seed = 7, turns = 300), playGame(seed = 7, turns = 300))
    }

    @Test
    fun `thousands of random moves never break the character sheet`() {
        var moves = 0
        for (seed in 1L..20L) moves += playGame(seed, turns = 250).size
        assertTrue(moves >= 4_000, "Only $moves moves were played")
    }

    private val catalog: MoveCatalog = MoveLoader.fromResources()

    /** Plays random moves with random choices and returns the list of what happened, checking the sheet after each step. */
    private fun playGame(seed: Long, turns: Int): List<String> {
        val random = Random(seed)
        val resolver = MoveResolver(catalog, SeededDice.fromSeed(seed))
        var character = newCharacter(random)
        val log = mutableListOf<String>()

        repeat(turns) {
            if (character.isEnded) character = newCharacter(random)
            val move = catalog.moves.random(random)
            val request = requestFor(move.id, move.roll, character, random) ?: return@repeat
            val (track, sheet) = request
            var step = try {
                resolver.make(sheet, move.id, track)
            } catch (e: IllegalArgumentException) {
                // Only rule refusals are expected here, such as Resupply while unprepared.
                assertTrue(move.blockedBy.any { it in sheet.debilities }, "${move.id} failed: ${e.message}")
                return@repeat
            }
            while (step !is MoveStep.Done) {
                step = when (step) {
                    is MoveStep.AwaitingReroll -> resolver.reroll(step, emptySet())
                    is MoveStep.AwaitingBurn -> resolver.decideBurn(step, random.nextBoolean())
                    is MoveStep.AwaitingChoice -> {
                        val affordable = step.choice.options.filter { option ->
                            option.effects.filterIsInstance<Effect.SpendExperience>()
                                .sumOf { it.amount } <= step.character.experience
                        }
                        if (affordable.size < step.choice.count) break
                        resolver.choose(step, affordable.shuffled(random).take(step.choice.count).map { it.id })
                    }
                    is MoveStep.Done -> step
                }
            }
            character = step.character
            check(character)
            log += "${move.id}:${step.record.roll?.outcome}:${step.record.chosen}"
        }
        return log
    }

    private fun newCharacter(random: Random): Character {
        val stats = listOf(3, 2, 2, 1, 1).shuffled(random)
        return Character.create(stats[0], stats[1], stats[2], stats[3], stats[4])
    }

    /** A request the move can be made with, starting any track it needs, or null to skip the move. */
    private fun requestFor(moveId: String, roll: MoveRoll, character: Character, random: Random): Pair<MoveRequest, Character>? {
        val move = checkNotNull(catalog[moveId])
        var sheet = character
        var trackId: String? = null
        val trackType = move.progressTrack
        if (trackType != null && move.effects.none { it is Effect.StartTrack }) {
            trackId = sheet.tracks.entries.firstOrNull { it.value.type == trackType }?.key
            if (trackId == null) {
                val (id, updated) = sheet.startTrack(trackType, "Random", ChallengeRank.entries.random(random))
                trackId = id
                sheet = updated
            }
        }
        if (move.id == "advance" && sheet.experience < 2) return null
        val source = (roll as? MoveRoll.Action)?.sources?.random(random)
        val request = MoveRequest(
            source = source,
            track = trackId,
            amount = random.nextInt(1, 4),
            newTrack = NewTrack("Random vow", ChallengeRank.entries.random(random)),
        )
        return request to sheet
    }

    private fun check(character: Character) {
        for (type in MeterType.entries) assertTrue(character.meters[type].value in 0..5, "$type out of range")
        assertTrue(character.momentum.value in Momentum.MIN..character.momentum.max, "${character.momentum}")
        assertEquals(character.debilities.count, character.momentum.debilities)
        for (track in character.tracks.values) assertTrue(track.ticks in 0..ProgressTrack.MAX_TICKS, "$track")
        assertTrue(character.experience >= 0)
    }
}
