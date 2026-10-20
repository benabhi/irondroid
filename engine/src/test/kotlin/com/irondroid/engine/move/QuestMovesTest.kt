package com.irondroid.engine.move

import com.irondroid.engine.progress.ProgressTrackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The quest moves and Advance shipped with the app (IRON-24), checked against the Ironsworn rules. */
class QuestMovesTest {
    private val catalog = MoveLoader.fromResources()

    private fun move(id: String) = checkNotNull(catalog[id]) { "Move $id not found" }

    private fun outcomes(id: String) = checkNotNull(move(id).outcomes)

    private fun optionEffects(choice: Choice?) = checkNotNull(choice).options.associate { it.id to it.effects }

    private val forsake = listOf(Effect.TriggerMove("forsake_your_vow"))

    @Test
    fun `the five quest moves load in both languages`() {
        val quest = catalog.moves.filter { it.category == MoveCategory.QUEST }.map { it.id }
        assertEquals(listOf("swear_an_iron_vow", "reach_a_milestone", "fulfill_your_vow", "forsake_your_vow", "advance"), quest)
        assertEquals("Jurar un voto de hierro", catalog.text("swear_an_iron_vow", "es")?.name)
        assertEquals("Avanzar", catalog.text("advance", "es")?.name)
        assertEquals("Advance", catalog.text("advance", "en")?.name)
    }

    @Test
    fun `swear an iron vow`() {
        val move = move("swear_an_iron_vow")
        assertEquals(ProgressTrackType.VOW, move.progressTrack)
        assertEquals(listOf(Effect.StartTrack), move.effects)
        val roll = assertIs<MoveRoll.Action>(move.roll)
        assertEquals(listOf(RollSource.HEART), roll.sources)
        assertEquals(listOf(RollBonus("sworn_to_bond", 1)), roll.bonuses)
        val outcomes = outcomes("swear_an_iron_vow")
        assertEquals(listOf(Effect.AdjustMomentum(2)), outcomes.strongHit.effects)
        assertEquals(listOf(Effect.AdjustMomentum(1)), outcomes.weakHit.effects)
        assertEquals(
            mapOf("press_on" to listOf(Effect.AdjustMomentum(-2)), "give_up" to forsake),
            optionEffects(outcomes.miss.choice),
        )
    }

    @Test
    fun `reach a milestone`() {
        val move = move("reach_a_milestone")
        assertEquals(MoveRoll.None, move.roll)
        assertEquals(ProgressTrackType.VOW, move.progressTrack)
        assertEquals(listOf(Effect.MarkProgress(1)), move.effects)
    }

    @Test
    fun `fulfill your vow`() {
        assertEquals(MoveRoll.Progress(ProgressTrackType.VOW), move("fulfill_your_vow").roll)
        val outcomes = outcomes("fulfill_your_vow")
        assertEquals(listOf(Effect.MarkExperience(0), Effect.EndTrack), outcomes.strongHit.effects)
        assertEquals(
            listOf(Effect.MarkExperience(-1), Effect.EndTrack, Effect.NextRollBonus(1, "swear_an_iron_vow")),
            outcomes.weakHit.effects,
        )
        assertEquals(
            mapOf(
                "recommit" to listOf(Effect.ClearProgress(keepBoxes = 1), Effect.RaiseRank),
                "give_up" to forsake,
            ),
            optionEffects(outcomes.miss.choice),
        )
    }

    @Test
    fun `forsake your vow`() {
        val move = move("forsake_your_vow")
        assertEquals(MoveRoll.None, move.roll)
        assertEquals(ProgressTrackType.VOW, move.progressTrack)
        assertEquals(
            listOf(Effect.TriggerMove("endure_stress", amountByRank = true), Effect.EndTrack),
            move.effects,
        )
    }

    @Test
    fun advance() {
        val move = move("advance")
        assertEquals(MoveRoll.None, move.roll)
        assertNull(move.progressTrack)
        assertEquals(
            mapOf(
                "new_asset" to listOf(Effect.SpendExperience(3)),
                "upgrade_asset" to listOf(Effect.SpendExperience(2)),
            ),
            optionEffects(move.choice),
        )
        assertEquals("Spend 3 experience to add a new asset.", catalog.text("advance", "en")?.options?.get("new_asset"))
    }
}
