package com.irondroid.engine.move

import com.irondroid.engine.debility.Debility
import com.irondroid.engine.meters.MeterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The suffer and fate moves shipped with the app (IRON-25), checked against the Ironsworn rules. */
class SufferFateMovesTest {
    private val catalog = MoveLoader.fromResources()

    private fun move(id: String) = checkNotNull(catalog[id]) { "Move $id not found" }

    private fun outcomes(id: String) = checkNotNull(move(id).outcomes)

    private fun optionEffects(choice: Choice?) = checkNotNull(choice).options.associate { it.id to it.effects }

    @Test
    fun `suffer and fate moves load in both languages`() {
        assertEquals(
            listOf("endure_harm", "face_death", "endure_stress", "face_desolation", "out_of_supply", "face_a_setback"),
            catalog.moves.filter { it.category == MoveCategory.SUFFER }.map { it.id },
        )
        assertEquals(listOf("pay_the_price", "ask_the_oracle"), catalog.moves.filter { it.category == MoveCategory.FATE }.map { it.id })
        assertEquals("Soportar daño", catalog.text("endure_harm", "es")?.name)
        assertEquals("Preguntar al oráculo", catalog.text("ask_the_oracle", "es")?.name)
    }

    @Test
    fun `every move named by another move now exists`() {
        assertEquals(emptySet(), catalog.unresolvedReferences())
        assertEquals(setOf("endure_harm", "endure_stress", "pay_the_price"), catalog.referencedTables())
    }

    @Test
    fun `endure harm`() {
        val move = move("endure_harm")
        assertEquals(listOf(Effect.Suffer(MeterType.HEALTH)), move.effects)
        val roll = assertIs<MoveRoll.Action>(move.roll)
        assertEquals(listOf(RollSource.HEALTH, RollSource.IRON), roll.sources)
        assertEquals(SourcePick.HIGHEST, roll.pick)
        val outcomes = outcomes("endure_harm")
        assertEquals(
            mapOf(
                "shake_it_off" to listOf(Effect.AdjustMomentum(-1), Effect.AdjustMeter(MeterType.HEALTH, 1)),
                "embrace_the_pain" to listOf(Effect.AdjustMomentum(1)),
            ),
            optionEffects(outcomes.strongHit.choice),
        )
        assertEquals(OutcomeDefinition(), outcomes.weakHit)
        assertEquals(listOf(Effect.AdjustMomentum(-1)), outcomes.miss.effects)
        assertEquals(MeterType.HEALTH, outcomes.miss.choice?.whenZero)
        assertEquals(
            mapOf(
                "mark_wounded" to listOf(Effect.MarkDebility(Debility.WOUNDED)),
                "mark_maimed" to listOf(Effect.MarkDebility(Debility.MAIMED)),
                "roll_consequence" to listOf(Effect.RollTable("endure_harm")),
            ),
            optionEffects(outcomes.miss.choice),
        )
    }

    @Test
    fun `endure stress`() {
        val move = move("endure_stress")
        assertEquals(listOf(Effect.Suffer(MeterType.SPIRIT)), move.effects)
        val roll = assertIs<MoveRoll.Action>(move.roll)
        assertEquals(listOf(RollSource.HEART, RollSource.SPIRIT), roll.sources)
        assertEquals(SourcePick.HIGHEST, roll.pick)
        val outcomes = outcomes("endure_stress")
        assertEquals(
            mapOf(
                "shake_it_off" to listOf(Effect.AdjustMomentum(-1), Effect.AdjustMeter(MeterType.SPIRIT, 1)),
                "embrace_the_darkness" to listOf(Effect.AdjustMomentum(1)),
            ),
            optionEffects(outcomes.strongHit.choice),
        )
        assertEquals(OutcomeDefinition(), outcomes.weakHit)
        assertEquals(listOf(Effect.AdjustMomentum(-1)), outcomes.miss.effects)
        assertEquals(MeterType.SPIRIT, outcomes.miss.choice?.whenZero)
        assertEquals(
            mapOf(
                "mark_shaken" to listOf(Effect.MarkDebility(Debility.SHAKEN)),
                "mark_corrupted" to listOf(Effect.MarkDebility(Debility.CORRUPTED)),
                "roll_consequence" to listOf(Effect.RollTable("endure_stress")),
            ),
            optionEffects(outcomes.miss.choice),
        )
    }

    @Test
    fun `face death and face desolation`() {
        for ((id, burden, bargain) in listOf(
            Triple("face_death", Debility.CURSED, "bargain_with_death"),
            Triple("face_desolation", Debility.TORMENTED, "dreaded_vision"),
        )) {
            assertEquals(listOf(RollSource.HEART), assertIs<MoveRoll.Action>(move(id).roll).sources)
            val outcomes = outcomes(id)
            assertEquals(OutcomeDefinition(), outcomes.strongHit)
            assertEquals(
                mapOf(
                    "noble_sacrifice" to listOf(Effect.EndCharacter),
                    bargain to listOf(Effect.MarkDebility(burden), Effect.TriggerMove("swear_an_iron_vow")),
                ),
                optionEffects(outcomes.weakHit.choice),
                id,
            )
            assertEquals(listOf(Effect.EndCharacter), outcomes.miss.effects)
        }
    }

    @Test
    fun `out of supply and face a setback`() {
        assertEquals(listOf(Effect.MarkDebility(Debility.UNPREPARED)), move("out_of_supply").effects)
        val setback = move("face_a_setback")
        assertEquals(MoveRoll.None, setback.roll)
        assertEquals(setOf("exchange_momentum", "lose_progress"), setback.optionIds)
    }

    @Test
    fun `pay the price and ask the oracle`() {
        assertEquals(
            mapOf(
                "obvious_outcome" to emptyList(),
                "ask_the_oracle" to listOf(Effect.AskOracle),
                "roll_on_table" to listOf(Effect.RollTable("pay_the_price")),
            ),
            optionEffects(move("pay_the_price").choice),
        )
        assertEquals(
            mapOf(
                "draw_a_conclusion" to emptyList(),
                "yes_no_question" to listOf(Effect.AskOracle),
                "pick_two" to listOf(Effect.AskOracle),
                "spark_an_idea" to emptyList(),
            ),
            optionEffects(move("ask_the_oracle").choice),
        )
        assertNull(move("ask_the_oracle").outcomes)
    }
}
