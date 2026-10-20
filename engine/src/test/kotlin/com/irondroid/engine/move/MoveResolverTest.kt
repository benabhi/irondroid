package com.irondroid.engine.move

import com.irondroid.engine.character.Character
import com.irondroid.engine.character.PendingBonus
import com.irondroid.engine.debility.Debilities
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.dice.FixedDice
import com.irondroid.engine.meters.Meter
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.momentum.Momentum
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrack
import com.irondroid.engine.progress.ProgressTrackType
import com.irondroid.engine.roll.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Plays whole moves from the shipped catalog with fixed dice (IRON-26). */
class MoveResolverTest {
    private val catalog = MoveLoader.fromResources()

    /** Edge 2, heart 1, iron 3, shadow 2, wits 1; health, spirit and supply 5; momentum +2. */
    private val hero = Character.create(edge = 2, heart = 1, iron = 3, shadow = 2, wits = 1)

    private fun resolver(vararg dice: Int) = MoveResolver(catalog, FixedDice(*dice))

    private val edge = MoveRequest(source = RollSource.EDGE)

    private fun Character.withMeter(type: MeterType, value: Int) = copy(meters = meters.with(Meter(type, value)))

    private fun Character.withTrack(type: ProgressTrackType, rank: ChallengeRank, ticks: Int = 0): Pair<String, Character> {
        val (id, character) = startTrack(type, "Test track", rank)
        return id to character.copy(tracks = character.tracks + (id to ProgressTrack("Test track", type, rank, ticks)))
    }

    // --- Action rolls, outcomes and choices ---

    @Test
    fun `a strong hit applies its effects and is done`() {
        val step = resolver(5, 3, 4).make(hero, "face_danger", edge) // 5 + 2 = 7 against 3 and 4.
        val done = assertIs<MoveStep.Done>(step)
        assertEquals(Outcome.STRONG_HIT, done.record.roll?.outcome)
        assertEquals(3, done.character.momentum.value)
        assertEquals(emptyList(), done.record.followUps)
    }

    @Test
    fun `a weak hit with options waits for the player`() {
        val step = assertIs<MoveStep.AwaitingChoice>(resolver(3, 2, 9).make(hero, "face_danger", edge)) // 5 against 2 and 9.
        assertEquals(listOf("lose_momentum", "endure_harm", "endure_stress", "lose_supply"), step.choice.options.map { it.id })
        assertEquals(hero, step.character) // Nothing applied yet.

        val supply = assertIs<MoveStep.Done>(resolver().choose(step, listOf("lose_supply")))
        assertEquals(4, supply.character.meters.supply.value)
        assertEquals(listOf("lose_supply"), supply.record.chosen)

        val harm = assertIs<MoveStep.Done>(resolver().choose(step, listOf("endure_harm")))
        assertEquals(listOf(FollowUp.MakeMove("endure_harm", 1)), harm.record.followUps)
    }

    @Test
    fun `a miss asks to pay the price`() {
        val done = assertIs<MoveStep.Done>(resolver(1, 9, 9).make(hero, "face_danger", edge))
        assertEquals(Outcome.MISS, done.record.roll?.outcome)
        assertTrue(done.record.roll!!.isMatch)
        assertEquals(listOf(FollowUp.MakeMove("pay_the_price")), done.record.followUps)
    }

    @Test
    fun `burning momentum is offered when it helps and the player decides`() {
        val strong = hero.copy(momentum = Momentum(8))
        val step = assertIs<MoveStep.AwaitingBurn>(resolver(1, 5, 7).make(strong, "face_danger", edge)) // 3: a miss.
        assertEquals(Outcome.STRONG_HIT, step.burnOutcome)

        val burned = assertIs<MoveStep.Done>(resolver().decideBurn(step, burn = true))
        assertEquals(Outcome.STRONG_HIT, burned.record.roll?.outcome)
        assertEquals(3, burned.character.momentum.value) // Reset to 2, then +1 from the strong hit.

        val kept = assertIs<MoveStep.Done>(resolver().decideBurn(step, burn = false))
        assertEquals(Outcome.MISS, kept.record.roll?.outcome)
        assertEquals(8, kept.character.momentum.value)
        assertEquals(listOf(FollowUp.MakeMove("pay_the_price")), kept.record.followUps)
    }

    @Test
    fun `the source must be picked when the move offers several`() {
        assertFailsWith<IllegalArgumentException> { resolver(1, 1, 1).make(hero, "face_danger") }
        assertFailsWith<IllegalArgumentException> {
            resolver(1, 1, 1).make(hero, "face_danger", MoveRequest(source = RollSource.SUPPLY))
        }
    }

    @Test
    fun `a bonus for the next roll is used once`() {
        val advantage = assertIs<MoveStep.AwaitingChoice>(resolver(6, 1, 1).make(hero, "secure_an_advantage", edge))
        val prepared = resolver().choose(advantage, listOf("take_control")).character
        assertEquals(listOf(PendingBonus(1)), prepared.pendingBonuses)

        val step = resolver(3, 5, 6).make(prepared, "face_danger", edge) // 3 + 2 + 1 = 6: beats 5, ties 6.
        val roll = assertIs<RollRecord.Action>(step.record.roll)
        assertEquals(1, roll.roll.adds)
        assertEquals(Outcome.WEAK_HIT, roll.outcome)
        assertEquals(emptyList(), step.character.pendingBonuses)
    }

    @Test
    fun `situational bonuses are added only when they apply`() {
        val step = resolver(4, 5, 5).make(hero, "gather_information", MoveRequest(bonuses = setOf("community_or_bond")))
        assertEquals(6, assertIs<RollRecord.Action>(step.record.roll).roll.actionScore) // 4 + wits 1 + 1.
        assertFailsWith<IllegalArgumentException> {
            resolver(4, 5, 5).make(hero, "gather_information", MoveRequest(bonuses = setOf("bribe")))
        }
    }

    @Test
    fun `choices need the right number of different options`() {
        val camp = assertIs<MoveStep.AwaitingChoice>(resolver(2, 3, 4).make(hero.withMeter(MeterType.SPIRIT, 3), "make_camp"))
        assertEquals(2, camp.choice.count) // Rolled +supply 5: strong hit.
        assertFailsWith<IllegalArgumentException> { resolver().choose(camp, listOf("relax")) }
        assertFailsWith<IllegalArgumentException> { resolver().choose(camp, listOf("relax", "relax")) }
        assertFailsWith<IllegalArgumentException> { resolver().choose(camp, listOf("relax", "nap")) }

        val done = resolver().choose(camp, listOf("relax", "focus")).character
        assertEquals(4, done.meters.spirit.value)
        assertEquals(3, done.momentum.value)
    }

    // --- Progress tracks and vows ---

    @Test
    fun `a journey marks progress by its rank`() {
        val (journey, traveler) = hero.withTrack(ProgressTrackType.JOURNEY, ChallengeRank.DANGEROUS)
        val done = assertIs<MoveStep.Done>(resolver(4, 3, 8).make(traveler, "undertake_a_journey", MoveRequest(track = journey)))
        assertEquals(8, done.character.tracks.getValue(journey).ticks) // Weak hit: 2 progress.
        assertEquals(4, done.character.meters.supply.value)
        assertFailsWith<IllegalArgumentException> { resolver(4, 3, 8).make(traveler, "undertake_a_journey") }
    }

    @Test
    fun `swearing a vow opens its track`() {
        val request = MoveRequest(newTrack = NewTrack("Avenge my kin", ChallengeRank.FORMIDABLE))
        val done = assertIs<MoveStep.Done>(resolver(6, 2, 3).make(hero, "swear_an_iron_vow", request))
        val id = checkNotNull(done.record.track)
        assertEquals(ProgressTrack("Avenge my kin", ProgressTrackType.VOW, ChallengeRank.FORMIDABLE), done.character.tracks[id])
        assertEquals(4, done.character.momentum.value)
        assertFailsWith<IllegalArgumentException> { resolver(6, 2, 3).make(hero, "swear_an_iron_vow") }
    }

    @Test
    fun `fulfilling a vow marks experience by rank and closes it`() {
        val (vow, sworn) = hero.withTrack(ProgressTrackType.VOW, ChallengeRank.FORMIDABLE, ticks = 28) // 7 boxes.
        val strongStep = resolver(3, 4).make(sworn, "fulfill_your_vow", MoveRequest(track = vow))
        val progress = assertIs<RollRecord.Progress>(strongStep.record.roll)
        assertEquals(vow, progress.track)
        assertFalse(progress.isMatch)
        val strong = strongStep.character
        assertEquals(3, strong.experience)
        assertFalse(vow in strong.tracks)

        val weak = resolver(3, 8).make(sworn, "fulfill_your_vow", MoveRequest(track = vow)).character
        assertEquals(2, weak.experience)
        assertEquals(listOf(PendingBonus(1, "swear_an_iron_vow")), weak.pendingBonuses)

        val miss = assertIs<MoveStep.AwaitingChoice>(resolver(9, 9).make(sworn, "fulfill_your_vow", MoveRequest(track = vow)))
        val recommitted = resolver().choose(miss, listOf("recommit")).character.tracks.getValue(vow)
        assertEquals(4, recommitted.ticks)
        assertEquals(ChallengeRank.EXTREME, recommitted.rank)
    }

    @Test
    fun `forsaking a vow ends it and asks for stress by rank`() {
        val (vow, sworn) = hero.withTrack(ProgressTrackType.VOW, ChallengeRank.DANGEROUS)
        val done = assertIs<MoveStep.Done>(resolver().make(sworn, "forsake_your_vow", MoveRequest(track = vow)))
        assertFalse(vow in done.character.tracks)
        assertEquals(listOf(FollowUp.MakeMove("endure_stress", 2)), done.record.followUps)
    }

    @Test
    fun `advancing spends experience it has`() {
        val step = assertIs<MoveStep.AwaitingChoice>(resolver().make(hero.copy(experience = 3), "advance"))
        assertEquals(0, resolver().choose(step, listOf("new_asset")).character.experience)
        val poor = assertIs<MoveStep.AwaitingChoice>(resolver().make(hero.copy(experience = 1), "advance"))
        assertFailsWith<IllegalArgumentException> { resolver().choose(poor, listOf("new_asset")) }
    }

    // --- Suffering ---

    @Test
    fun `endure harm takes the amount and spills into momentum`() {
        val hurt = hero.withMeter(MeterType.HEALTH, 2)
        // 3 harm at health 2: health 0 and -1 momentum. Rolls iron 3 (higher than health 0): 1 + 3 = 4, a miss.
        val step = assertIs<MoveStep.AwaitingChoice>(resolver(1, 9, 9).make(hurt, "endure_harm", MoveRequest(amount = 3)))
        assertEquals(RollSource.IRON, assertIs<RollRecord.Action>(step.record.roll).source)
        assertEquals(0, step.character.meters.health.value)
        assertEquals(0, step.character.momentum.value) // 2 - 1 spilled - 1 from the miss.
        assertEquals(MeterType.HEALTH, step.choice.whenZero)

        val wounded = resolver().choose(step, listOf("mark_wounded")).character
        assertTrue(Debility.WOUNDED in wounded.debilities)
        assertEquals(9, wounded.momentum.max)
    }

    @Test
    fun `endure harm above zero health skips the debility choice`() {
        val done = assertIs<MoveStep.Done>(resolver(1, 9, 9).make(hero, "endure_harm", MoveRequest(amount = 1)))
        assertEquals(4, done.character.meters.health.value)
        assertEquals(1, done.character.momentum.value)
        assertFailsWith<IllegalArgumentException> { resolver(1, 9, 9).make(hero, "endure_harm") }
    }

    @Test
    fun `losing momentum past the minimum asks for a setback`() {
        val low = hero.copy(momentum = Momentum(-6))
        val step = assertIs<MoveStep.AwaitingChoice>(resolver(3, 2, 9).make(low, "face_danger", edge))
        val done = resolver().choose(step, listOf("lose_momentum"))
        assertEquals(listOf(FollowUp.MakeMove("face_a_setback", 1)), done.record.followUps)
    }

    @Test
    fun `running out of supply leads to out of supply and blocks resupplying`() {
        val step = assertIs<MoveStep.AwaitingChoice>(resolver(3, 2, 9).make(hero.withMeter(MeterType.SUPPLY, 1), "face_danger", edge))
        val empty = resolver().choose(step, listOf("lose_supply"))
        assertEquals(listOf(FollowUp.MakeMove("out_of_supply")), empty.record.followUps)

        val unprepared = resolver().make(empty.character, "out_of_supply").character
        assertTrue(Debility.UNPREPARED in unprepared.debilities)
        assertEquals(9, unprepared.momentum.max)
        assertFailsWith<IllegalArgumentException> { resolver(6, 1, 1).make(unprepared, "resupply") }
    }

    @Test
    fun `supply cannot grow while unprepared`() {
        // No shipped move gains supply while unprepared (Resupply is blocked), so a tiny catalog checks the rule.
        val forage = """{ "version": 1, "moves": [{ "id": "forage", "category": "adventure", "roll": { "type": "none" },
            "effects": [{ "type": "meter", "meter": "supply", "amount": 2 }] }] }"""
        val text = """{ "version": 1, "moves": { "forage": { "name": "Forage", "trigger": "When..." } } }"""
        val small = MoveResolver(MoveLoader.load(forage, mapOf("en" to text, "es" to text)), FixedDice())
        val unprepared = hero.withMeter(MeterType.SUPPLY, 0).withDebilities(Debilities(setOf(Debility.UNPREPARED)))
        assertEquals(0, small.make(unprepared, "forage").character.meters.supply.value)
        assertEquals(2, small.make(hero.withMeter(MeterType.SUPPLY, 0), "forage").character.meters.supply.value)
    }

    @Test
    fun `heal clears wounded and restores health`() {
        val wounded = hero.withMeter(MeterType.HEALTH, 2).withDebilities(Debilities(setOf(Debility.WOUNDED)))
        val done = assertIs<MoveStep.Done>(resolver(6, 2, 3).make(wounded, "heal", MoveRequest(source = RollSource.WITS)))
        assertFalse(Debility.WOUNDED in done.character.debilities)
        assertEquals(4, done.character.meters.health.value)
        assertEquals(10, done.character.momentum.max)
    }

    @Test
    fun `a miss facing death ends the story`() {
        val done = assertIs<MoveStep.Done>(resolver(1, 9, 9).make(hero, "face_death"))
        assertTrue(done.character.isEnded)
        assertEquals(listOf(FollowUp.CharacterEnded), done.record.followUps)
        assertFailsWith<IllegalArgumentException> { resolver(5, 1, 1).make(done.character, "face_danger", edge) }
    }

    @Test
    fun `fate moves hand the oracle to the caller`() {
        val price = assertIs<MoveStep.AwaitingChoice>(resolver().make(hero, "pay_the_price"))
        assertEquals(listOf(FollowUp.RollOnTable("pay_the_price")), resolver().choose(price, listOf("roll_on_table")).record.followUps)
        val oracle = assertIs<MoveStep.AwaitingChoice>(resolver().make(hero, "ask_the_oracle"))
        assertEquals(listOf(FollowUp.AskOracle), resolver().choose(oracle, listOf("yes_no_question")).record.followUps)
    }
}
