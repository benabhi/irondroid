package com.irondroid.engine.move

import com.irondroid.engine.debility.Debility
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.progress.ProgressTrackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The adventure moves shipped with the app (IRON-23), checked against the Ironsworn rules. */
class AdventureMovesTest {
    private val catalog = MoveLoader.fromResources()

    private fun move(id: String) = checkNotNull(catalog[id]) { "Move $id not found" }

    private fun outcomes(id: String) = checkNotNull(move(id).outcomes)

    private fun sources(id: String) = assertIs<MoveRoll.Action>(move(id).roll).sources

    private fun optionEffects(choice: Choice?) = checkNotNull(choice).options.associate { it.id to it.effects }

    private val payThePrice = listOf(Effect.TriggerMove("pay_the_price"))
    private val allStats = listOf(RollSource.EDGE, RollSource.HEART, RollSource.IRON, RollSource.SHADOW, RollSource.WITS)

    @Test
    fun `the eight adventure moves load in both languages`() {
        val adventure = catalog.moves.filter { it.category == MoveCategory.ADVENTURE }.map { it.id }
        assertEquals(
            listOf(
                "face_danger", "secure_an_advantage", "gather_information", "heal",
                "resupply", "make_camp", "undertake_a_journey", "reach_your_destination",
            ),
            adventure,
        )
        for (id in adventure) {
            assertTrue(catalog.text(id, "en")!!.name.isNotBlank())
            assertTrue(catalog.text(id, "es")!!.name.isNotBlank())
        }
        assertEquals("Enfrentar el peligro", catalog.text("face_danger", "es")?.name)
        assertEquals("Llegar a destino", catalog.text("reach_your_destination", "es")?.name)
    }

    @Test
    fun `face danger`() {
        assertEquals(allStats, sources("face_danger"))
        val outcomes = outcomes("face_danger")
        assertEquals(listOf(Effect.AdjustMomentum(1)), outcomes.strongHit.effects)
        assertEquals(1, outcomes.weakHit.choice?.count)
        assertEquals(
            mapOf(
                "lose_momentum" to listOf(Effect.AdjustMomentum(-1)),
                "endure_harm" to listOf(Effect.TriggerMove("endure_harm", 1)),
                "endure_stress" to listOf(Effect.TriggerMove("endure_stress", 1)),
                "lose_supply" to listOf(Effect.AdjustMeter(MeterType.SUPPLY, -1)),
            ),
            optionEffects(outcomes.weakHit.choice),
        )
        assertEquals(payThePrice, outcomes.miss.effects)
    }

    @Test
    fun `secure an advantage`() {
        assertEquals(allStats, sources("secure_an_advantage"))
        val outcomes = outcomes("secure_an_advantage")
        assertEquals(
            mapOf(
                "take_control" to listOf(Effect.NextRollBonus(1)),
                "prepare_to_act" to listOf(Effect.AdjustMomentum(2)),
            ),
            optionEffects(outcomes.strongHit.choice),
        )
        assertEquals(listOf(Effect.AdjustMomentum(1)), outcomes.weakHit.effects)
        assertEquals(payThePrice, outcomes.miss.effects)
    }

    @Test
    fun `gather information`() {
        val roll = assertIs<MoveRoll.Action>(move("gather_information").roll)
        assertEquals(listOf(RollSource.WITS), roll.sources)
        assertEquals(listOf(RollBonus("community_or_bond", 1)), roll.bonuses)
        val outcomes = outcomes("gather_information")
        assertEquals(listOf(Effect.AdjustMomentum(2)), outcomes.strongHit.effects)
        assertEquals(listOf(Effect.AdjustMomentum(1)), outcomes.weakHit.effects)
        assertEquals(payThePrice, outcomes.miss.effects)
    }

    @Test
    fun heal() {
        assertEquals(listOf(RollSource.WITS, RollSource.IRON), sources("heal"))
        val outcomes = outcomes("heal")
        val care = listOf(Effect.ClearDebility(Debility.WOUNDED), Effect.AdjustMeter(MeterType.HEALTH, 2))
        assertEquals(care, outcomes.strongHit.effects)
        assertEquals(care, outcomes.weakHit.effects)
        assertEquals(
            mapOf(
                "lose_supply" to listOf(Effect.AdjustMeter(MeterType.SUPPLY, -1)),
                "lose_momentum" to listOf(Effect.AdjustMomentum(-1)),
            ),
            optionEffects(outcomes.weakHit.choice),
        )
        assertEquals(payThePrice, outcomes.miss.effects)
    }

    @Test
    fun resupply() {
        assertEquals(listOf(RollSource.WITS), sources("resupply"))
        assertEquals(listOf(Debility.UNPREPARED), move("resupply").blockedBy)
        val outcomes = outcomes("resupply")
        assertEquals(listOf(Effect.AdjustMeter(MeterType.SUPPLY, 2)), outcomes.strongHit.effects)
        assertEquals(
            mapOf(
                "take_one_supply" to listOf(Effect.AdjustMeter(MeterType.SUPPLY, 1), Effect.AdjustMomentum(-1)),
                "take_two_supply" to listOf(Effect.AdjustMeter(MeterType.SUPPLY, 2), Effect.AdjustMomentum(-2)),
            ),
            optionEffects(outcomes.weakHit.choice),
        )
        assertEquals(payThePrice, outcomes.miss.effects)
    }

    @Test
    fun `make camp`() {
        assertEquals(listOf(RollSource.SUPPLY), sources("make_camp"))
        val outcomes = outcomes("make_camp")
        val options = mapOf(
            "recuperate" to listOf(Effect.AdjustMeter(MeterType.HEALTH, 1)),
            "partake" to listOf(Effect.AdjustMeter(MeterType.SUPPLY, -1), Effect.AdjustMeter(MeterType.HEALTH, 1)),
            "relax" to listOf(Effect.AdjustMeter(MeterType.SPIRIT, 1)),
            "focus" to listOf(Effect.AdjustMomentum(1)),
            "prepare" to listOf(Effect.NextRollBonus(1, "undertake_a_journey")),
        )
        assertEquals(2, outcomes.strongHit.choice?.count)
        assertEquals(options, optionEffects(outcomes.strongHit.choice))
        assertEquals(1, outcomes.weakHit.choice?.count)
        assertEquals(options, optionEffects(outcomes.weakHit.choice))
        assertEquals(payThePrice, outcomes.miss.effects)
    }

    @Test
    fun `undertake a journey`() {
        val move = move("undertake_a_journey")
        assertEquals(ProgressTrackType.JOURNEY, move.progressTrack)
        assertEquals(listOf(RollBonus("bonded_community", 1)), assertIs<MoveRoll.Action>(move.roll).bonuses)
        val outcomes = outcomes("undertake_a_journey")
        assertEquals(
            mapOf(
                "use_resources" to listOf(Effect.MarkProgress(1)),
                "move_at_speed" to listOf(Effect.MarkProgress(1), Effect.AdjustMomentum(1), Effect.AdjustMeter(MeterType.SUPPLY, -1)),
            ),
            optionEffects(outcomes.strongHit.choice),
        )
        assertEquals(listOf(Effect.MarkProgress(1), Effect.AdjustMeter(MeterType.SUPPLY, -1)), outcomes.weakHit.effects)
        assertEquals(payThePrice, outcomes.miss.effects)
    }

    @Test
    fun `reach your destination`() {
        assertEquals(MoveRoll.Progress(ProgressTrackType.JOURNEY), move("reach_your_destination").roll)
        val outcomes = outcomes("reach_your_destination")
        assertEquals(
            mapOf(
                "make_another_move" to listOf(Effect.NextRollBonus(1)),
                "take_momentum" to listOf(Effect.AdjustMomentum(1)),
            ),
            optionEffects(outcomes.strongHit.choice),
        )
        assertEquals(OutcomeDefinition(), outcomes.weakHit)
        assertEquals(
            mapOf(
                "journey_ends" to emptyList(),
                "journey_continues" to listOf(Effect.ClearProgress(keepBoxes = 1), Effect.RaiseRank),
            ),
            optionEffects(outcomes.miss.choice),
        )
        assertNull(outcomes.miss.effects.firstOrNull())
    }
}
