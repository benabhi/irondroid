package com.irondroid.engine.move

import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.progress.ProgressTrackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveLoaderTest {
    private val catalog = MoveLoader.fromResources("moves/sample")

    /** One minimal valid move; tests break it on purpose. */
    private val validMove = """
        { "id": "face_danger", "category": "adventure",
          "roll": { "type": "action", "sources": ["edge"] },
          "outcomes": { "strong_hit": {}, "weak_hit": {}, "miss": {} } }
    """
    private val validText = """
        "face_danger": { "name": "Face Danger", "trigger": "When...", "strong_hit": "a", "weak_hit": "b", "miss": "c" }
    """

    private fun structure(vararg moves: String) = """{ "version": 1, "moves": [${moves.joinToString(",")}] }"""

    private fun texts(vararg entries: String) = """{ "version": 1, "moves": { ${entries.joinToString(",")} } }"""

    private fun bothLanguages(vararg entries: String) = mapOf("en" to texts(*entries), "es" to texts(*entries))

    private fun errorsOf(structure: String, texts: Map<String, String>): List<String> =
        assertFailsWith<MoveDataException> { MoveLoader.load(structure, texts) }.errors

    @Test
    fun `sample moves load from the classpath`() {
        assertEquals(listOf("face_danger", "secure_an_advantage", "fulfill_your_vow", "reach_a_milestone"), catalog.moves.map { it.id })
        assertEquals(setOf("en", "es"), catalog.languages)
    }

    @Test
    fun `an action move keeps its sources, effects and choices`() {
        val move = checkNotNull(catalog["face_danger"])
        assertEquals(MoveCategory.ADVENTURE, move.category)
        assertEquals(5, assertIs<MoveRoll.Action>(move.roll).sources.size)
        val outcomes = checkNotNull(move.outcomes)
        assertEquals(listOf(Effect.AdjustMomentum(1)), outcomes.strongHit.effects)
        val choice = checkNotNull(outcomes.weakHit.choice)
        assertEquals(1, choice.count)
        assertEquals(listOf("lose_momentum", "endure_harm", "endure_stress", "lose_supply"), choice.options.map { it.id })
        assertEquals(listOf(Effect.AdjustMeter(MeterType.SUPPLY, -1)), choice.options.last().effects)
        assertEquals(listOf(Effect.TriggerMove("pay_the_price")), outcomes.miss.effects)
    }

    @Test
    fun `progress and no-roll moves load too`() {
        assertEquals(MoveRoll.Progress(ProgressTrackType.VOW), catalog["fulfill_your_vow"]?.roll)
        val milestone = checkNotNull(catalog["reach_a_milestone"])
        assertEquals(MoveRoll.None, milestone.roll)
        assertNull(milestone.outcomes)
        assertEquals(listOf(Effect.MarkProgress(1)), milestone.effects)
    }

    @Test
    fun `texts come in both languages`() {
        assertEquals("Face Danger", catalog.text("face_danger", "en")?.name)
        assertEquals("Enfrentar el peligro", catalog.text("face_danger", "es")?.name)
        assertEquals("Prepararte: tomá +2 de impulso.", catalog.text("secure_an_advantage", "es")?.options?.get("take_momentum"))
        assertNull(catalog.text("reach_a_milestone", "en")?.strongHit)
        assertNull(catalog.text("face_danger", "fr"))
    }

    @Test
    fun `moves named by effects but not loaded are listed`() {
        assertEquals(setOf("endure_harm", "endure_stress", "pay_the_price"), catalog.unresolvedReferences())
    }

    @Test
    fun `a valid minimal catalog loads`() {
        val loaded = MoveLoader.load(structure(validMove), bothLanguages(validText))
        assertEquals(listOf("face_danger"), loaded.moves.map { it.id })
        assertTrue(loaded.unresolvedReferences().isEmpty())
    }

    @Test
    fun `structure errors are all reported together`() {
        val errors = errorsOf(
            structure(
                validMove,
                validMove,
                """{ "id": "Bad-Id", "category": "fate", "roll": { "type": "none" } }""",
                """{ "id": "no_sources", "category": "fate", "roll": { "type": "action", "sources": [] },
                     "outcomes": { "strong_hit": {}, "weak_hit": {}, "miss": {} } }""",
                """{ "id": "no_outcomes", "category": "fate", "roll": { "type": "progress", "track": "vow" } }""",
                """{ "id": "extra_outcomes", "category": "fate", "roll": { "type": "none" },
                     "outcomes": { "strong_hit": {}, "weak_hit": {}, "miss": {} } }""",
                """{ "id": "bad_choice", "category": "fate", "roll": { "type": "action", "sources": ["iron"] },
                     "outcomes": { "strong_hit": { "choice": { "count": 3, "options": [{ "id": "a" }, { "id": "a" }] } },
                                   "weak_hit": {}, "miss": { "effects": [{ "type": "momentum", "amount": 0 }] } } }""",
            ),
            mapOf("en" to texts(), "es" to texts()),
        )
        val expected = listOf(
            "Move face_danger is defined more than once",
            "Move Bad-Id: id must be snake_case",
            "Move no_sources: action roll needs at least one source",
            "Move no_outcomes: a rolled move needs outcomes",
            "Move extra_outcomes: a move without a roll has no outcomes",
            "Move bad_choice: option a is repeated",
            "Move bad_choice: cannot choose 3 of 2 options",
            "Move bad_choice: momentum effect of 0",
        )
        expected.forEach { line -> assertTrue(line in errors, "Missing \"$line\" in $errors") }
    }

    @Test
    fun `unknown effect types, fields and values are rejected`() {
        val unknownEffect = validMove.replace("\"strong_hit\": {}", "\"strong_hit\": { \"effects\": [{ \"type\": \"teleport\" }] }")
        assertTrue(errorsOf(structure(unknownEffect), bothLanguages(validText)).single().startsWith("moves.json:"))

        val unknownField = validMove.replace("\"category\"", "\"colour\": \"red\", \"category\"")
        assertTrue(errorsOf(structure(unknownField), bothLanguages(validText)).single().startsWith("moves.json:"))

        val unknownStat = validMove.replace("[\"edge\"]", "[\"charm\"]")
        assertTrue(errorsOf(structure(unknownStat), bothLanguages(validText)).single().startsWith("moves.json:"))
    }

    @Test
    fun `broken json says which file failed`() {
        val errors = errorsOf(structure(validMove), mapOf("en" to texts(validText), "es" to "{ not json"))
        assertTrue(errors.single().startsWith("moves.es.json:"), errors.toString())
    }

    @Test
    fun `texts must match the moves in every language`() {
        val withOption = validMove.replace(
            "\"strong_hit\": {}",
            "\"strong_hit\": { \"choice\": { \"count\": 1, \"options\": [{ \"id\": \"pick_me\" }] } }",
        )
        val errors = errorsOf(
            structure(withOption),
            mapOf(
                "en" to texts(
                    """"face_danger": { "name": "Face Danger", "trigger": "When...", "strong_hit": "a", "weak_hit": "b",
                                        "options": { "other": "x" } }""",
                    """"ghost": { "name": "Ghost", "trigger": "Boo" }""",
                ),
                "es" to texts(),
            ),
        )
        val expected = listOf(
            "moves.en.json: text for unknown move ghost",
            "moves.en.json, move face_danger: missing miss text",
            "moves.en.json, move face_danger: missing text for option pick_me",
            "moves.en.json, move face_danger: text for unknown option other",
            "moves.es.json: no text for move face_danger",
        )
        expected.forEach { line -> assertTrue(line in errors, "Missing \"$line\" in $errors") }
    }

    @Test
    fun `both languages are required`() {
        val errors = errorsOf(structure(validMove), mapOf("en" to texts(validText)))
        assertEquals(listOf("moves.es.json: missing"), errors)
    }

    @Test
    fun `a missing resource is reported`() {
        val errors = assertFailsWith<MoveDataException> { MoveLoader.fromResources("moves/nowhere") }.errors
        assertEquals(listOf("moves/nowhere/moves.json: file not found"), errors)
    }

    @Test
    fun `new format pieces are validated`() {
        val errors = errorsOf(
            structure(
                """{ "id": "no_track", "category": "quest", "roll": { "type": "none" }, "effects": [{ "type": "raise_rank" }] }""",
                """{ "id": "mixed_options", "category": "adventure", "roll": { "type": "action", "sources": ["wits"],
                       "bonuses": [{ "id": "near_home", "amount": 0 }] },
                     "outcomes": {
                       "strong_hit": { "choice": { "count": 1, "options": [{ "id": "rest", "effects": [{ "type": "momentum", "amount": 1 }] }] } },
                       "weak_hit": { "choice": { "count": 1, "options": [{ "id": "rest", "effects": [{ "type": "momentum", "amount": 2 }] }] } },
                       "miss": { "effects": [{ "type": "clear_progress", "keep_boxes": 1 }] } } }""",
            ),
            bothLanguages(
                """"no_track": { "name": "No track", "trigger": "When..." }""",
                """"mixed_options": { "name": "Mixed", "trigger": "When...", "strong_hit": "a", "weak_hit": "b", "miss": "c",
                                      "options": { "rest": "Rest" }, "bonuses": { "far_away": "x" } }""",
            ),
        )
        val expected = listOf(
            "Move no_track: progress effects need the move's track",
            "Move mixed_options: option rest has different effects in different outcomes",
            "Move mixed_options: bonus near_home needs amount >= 1",
            "Move mixed_options: progress effects need the move's track",
            "moves.en.json, move mixed_options: missing text for bonus near_home",
            "moves.en.json, move mixed_options: text for unknown bonus far_away",
        )
        expected.forEach { line -> assertTrue(line in errors, "Missing \"$line\" in $errors") }
    }

    @Test
    fun `the same option may repeat across outcomes with one text`() {
        val shared = """{ "count": 1, "options": [{ "id": "rest", "effects": [{ "type": "momentum", "amount": 1 }] }] }"""
        val move = validMove.replace("\"strong_hit\": {}", "\"strong_hit\": { \"choice\": $shared }")
            .replace("\"weak_hit\": {}", "\"weak_hit\": { \"choice\": $shared }")
        val text = validText.replace("\"miss\": \"c\"", "\"miss\": \"c\", \"options\": { \"rest\": \"Rest\" }")
        assertEquals(setOf("rest"), MoveLoader.load(structure(move), bothLanguages(text))["face_danger"]?.optionIds)
    }

    @Test
    fun `vow and experience effects are validated`() {
        val errors = errorsOf(
            structure(
                """{ "id": "trackless", "category": "quest", "roll": { "type": "none" },
                     "effects": [{ "type": "end_track" }, { "type": "mark_experience" },
                                 { "type": "trigger_move", "move": "endure_stress", "amount_by_rank": true }] }""",
                """{ "id": "odd_values", "category": "quest", "track": "vow", "roll": { "type": "none" },
                     "effects": [{ "type": "mark_experience", "rank_offset": 1 }, { "type": "spend_experience", "amount": 0 },
                                 { "type": "trigger_move", "move": "endure_stress", "amount": 2, "amount_by_rank": true }] }""",
                """{ "id": "rolled_choice", "category": "quest", "roll": { "type": "action", "sources": ["heart"] },
                     "choice": { "count": 1, "options": [{ "id": "a" }] },
                     "outcomes": { "strong_hit": {}, "weak_hit": {}, "miss": {} } }""",
            ),
            mapOf("en" to texts(), "es" to texts()),
        )
        val expected = listOf(
            "Move trackless: progress effects need the move's track",
            "Move odd_values: mark_experience offset must be -4..0",
            "Move odd_values: spend_experience needs amount >= 1",
            "Move odd_values: trigger_move has both amount and amount_by_rank",
            "Move rolled_choice: a rolled move puts its choices in outcomes",
        )
        expected.forEach { line -> assertTrue(line in errors, "Missing \"$line\" in $errors") }
        assertEquals(3, errors.count { it == "Move trackless: progress effects need the move's track" })
    }

    @Test
    fun `suffer and oracle pieces are validated`() {
        val errors = errorsOf(
            structure(
                """{ "id": "single_pick", "category": "suffer", "roll": { "type": "action", "sources": ["iron"], "pick": "highest" },
                     "outcomes": { "strong_hit": {}, "weak_hit": {}, "miss": { "effects": [{ "type": "roll_table", "table": "Bad Table" }] } } }""",
            ),
            mapOf("en" to texts(), "es" to texts()),
        )
        assertTrue("Move single_pick: picking the highest or lowest needs two sources or more" in errors, errors.toString())
        assertTrue("Move single_pick: roll_table with an invalid id Bad Table" in errors, errors.toString())
    }
}
