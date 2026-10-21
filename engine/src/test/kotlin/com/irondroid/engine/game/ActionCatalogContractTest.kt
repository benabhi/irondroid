package com.irondroid.engine.game

import com.irondroid.engine.action.ActionErrorCode
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.ActionPermission
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.action.UndoRole
import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import com.irondroid.engine.dice.DieRoll
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Checks every action of the catalog at once (IRON-44): argument validation, permissions, that reads
 * change nothing and that every play action can be undone. A new action must be added to the sample
 * calls below, so it gets the same checks.
 */
class ActionCatalogContractTest {
    private class QueuedDice : Dice {
        val queue = ArrayDeque<Int>()

        override fun roll(die: Die) = DieRoll(die, queue.removeFirstOrNull() ?: 1)
    }

    private class FakeGame(var current: Character) : GameAccess {
        override var history: ActionHistory<Character>? = null
        override val sheet: Character get() = current
        override val characterName = "Kira"

        override fun update(change: (Character) -> Character) {
            current = change(current)
        }

        override fun save() = Unit
    }

    /** A game with a character, a dangerous vow (`vow_1`) and the shaken debility marked. */
    private class Fixture(sheet: Character = PLAYED) {
        val dice = QueuedDice()
        val game = FakeGame(sheet)
        val actions = GameActions(game, MOVES, ORACLES, dice, "en")
        val dispatcher = actions.dispatcher()
        val history = ActionHistory(actions.choices, snapshot = { game.sheet }, restore = { game.current = it })

        init {
            game.history = history
            dispatcher.addListener(history)
        }

        fun call(name: String, json: String, caller: Caller = Caller.AI, confirmed: Boolean = false) =
            dispatcher.dispatch(name, json, caller, confirmed)
    }

    /** One valid call for each action that reads, with the dice it needs. */
    private val reads = mapOf(
        "get_character" to "{}",
        "get_tracks" to "{}",
        "get_scene" to "{}",
        "search_journal" to """{"query":"vow","limit":5}""",
        "get_move" to """{"move":"face_danger"}""",
        "get_rules" to """{"section":"oracles"}""",
        "list_moves" to """{"category":"adventure"}""",
        "suggest_move" to """{"move":"face_danger","why":"A risky climb"}""",
        "list_oracle_tables" to "{}",
        "save" to "{}",
    )

    /** One valid call for each play action that does not answer a choice, and the dice it rolls. */
    private val plays = mapOf(
        "create_character" to ("""{"edge":3,"heart":2,"iron":2,"shadow":1,"wits":1}""" to emptyList()),
        "roll_move" to ("""{"move":"face_danger","source":"edge"}""" to listOf(6, 1, 1)),
        "adjust_meter" to ("""{"meter":"health","amount":-2}""" to emptyList()),
        "mark_debility" to ("""{"debility":"wounded"}""" to emptyList()),
        "clear_debility" to ("""{"debility":"shaken"}""" to emptyList()),
        "create_progress_track" to ("""{"type":"journey","name":"To the north","rank":"formidable"}""" to emptyList()),
        "mark_progress" to ("""{"track":"vow_1","times":2}""" to emptyList()),
        "close_progress_track" to ("""{"track":"vow_1"}""" to emptyList()),
        "roll_progress" to ("""{"track":"vow_1"}""" to listOf(3, 4)),
        "ask_oracle" to ("""{"question":"Is it dark?","odds":"likely"}""" to listOf(50)),
        "roll_oracle_table" to ("""{"table":"pay_the_price"}""" to listOf(50)),
        "undo" to ("{}" to emptyList<Int>()),
    )

    /** One valid call for each sensitive action. */
    private val sensitive = mapOf(
        "set_stats" to """{"edge":4,"heart":2,"iron":2,"shadow":1,"wits":1}""",
        "edit_character" to """{"health":1}""",
    )

    /** Actions that work on the open choice; their own tests cover them with a choice open. */
    private val answers = setOf("suggest_option", "choose_option", "burn_momentum", "undo")

    private val catalog = Fixture().actions.catalog

    @Test
    fun `every action has sample calls, so none escapes these checks`() {
        val byPermission = catalog.actions.groupBy({ it.permission }, { it.name })
        assertEquals(reads.keys, byPermission.getValue(ActionPermission.READ).toSet())
        assertEquals(sensitive.keys, byPermission.getValue(ActionPermission.SENSITIVE).toSet())
        assertEquals(
            byPermission.getValue(ActionPermission.PLAY).toSet(),
            plays.keys + answers,
        )
        assertEquals(answers, catalog.actions.filter { it.answersChoice }.map { it.name }.toSet())
    }

    @Test
    fun `unknown parameters are refused by every action`() {
        val fixture = Fixture()
        for (action in catalog.actions) {
            val error = assertIs<DispatchResult.Failure>(fixture.call(action.name, """{"zzz_extra":1}"""), action.name).error
            assertEquals(ActionErrorCode.INVALID_ARGUMENTS, error.code, action.name)
            assertTrue("zzz_extra: unknown parameter" in error.details, "${action.name}: ${error.details}")
        }
        assertEquals(PLAYED, fixture.game.sheet)
        assertTrue(fixture.history.calls.all { it.role == UndoRole.NONE })
    }

    @Test
    fun `every missing required parameter is named, and nothing runs`() {
        val fixture = Fixture()
        for (action in catalog.actions.filter { it.parameters.required.isNotEmpty() }) {
            val error = assertIs<DispatchResult.Failure>(fixture.call(action.name, "{}"), action.name).error
            assertEquals(ActionErrorCode.INVALID_ARGUMENTS, error.code, action.name)
            assertEquals(action.parameters.required.map { "$it: required" }.toSet(), error.details.toSet(), action.name)
        }
        val wrongTypes = assertIs<DispatchResult.Failure>(fixture.call("adjust_meter", """{"meter":"luck","amount":"two"}""")).error
        assertEquals(2, wrongTypes.details.size)
        assertEquals(PLAYED, fixture.game.sheet)
    }

    @Test
    fun `sensitive actions wait for the player's confirmation, whoever asks`() {
        for ((name, json) in sensitive) {
            for (caller in Caller.entries) {
                val fixture = Fixture()
                assertIs<DispatchResult.NeedsConfirmation>(fixture.call(name, json, caller), "$name by $caller")
                assertEquals(PLAYED, fixture.game.sheet, name)
            }
            val fixture = Fixture()
            assertIs<DispatchResult.Success>(fixture.call(name, json, Caller.PLAYER, confirmed = true), name)
            assertNotEquals(PLAYED, fixture.game.sheet, name)
            val undo = assertIs<DispatchResult.Failure>(fixture.call("undo", "{}"), name).error
            assertTrue(undo.message.contains("cannot be undone"), "$name: ${undo.message}")
        }
    }

    @Test
    fun `reads change nothing and are never undone`() {
        for ((name, json) in reads) {
            val fixture = Fixture()
            assertIs<DispatchResult.Success>(fixture.call(name, json), name)
            assertEquals(PLAYED, fixture.game.sheet, name)
            assertEquals(UndoRole.NONE, fixture.history.calls.single().role, name)
        }
    }

    @Test
    fun `every play action that changes the game is undone back to the sheet before it`() {
        for ((name, sample) in plays - "undo") {
            val (json, values) = sample
            val start = if (name == "create_character") Character.create(0, 0, 0, 0, 0) else PLAYED
            val fixture = Fixture(start)
            fixture.dice.queue += values
            val result = assertIs<DispatchResult.Success>(fixture.call(name, json), name)
            val action = result.action
            if (action.changesState) {
                assertNotEquals(start, fixture.game.sheet, "$name changed nothing")
                assertTrue(result.result.changes.isNotEmpty(), "$name reports no changes")
                assertIs<DispatchResult.Success>(fixture.call("undo", "{}", Caller.PLAYER), name)
                assertEquals(start, fixture.game.sheet, "$name was not fully undone")
            } else {
                // Dice-only actions, such as oracles, leave the sheet alone and there is nothing to undo.
                assertEquals(start, fixture.game.sheet, name)
                assertIs<DispatchResult.Failure>(fixture.call("undo", "{}"), name)
            }
        }
    }

    @Test
    fun `every action describes itself completely for function calling`() {
        val json = catalog.toJson()
        assertEquals(json, Json.parseToJsonElement(json.toString()))
        for (action in catalog.actions) {
            assertTrue(action.fullDescription.startsWith(action.description.trim().removeSuffix(".")), action.name)
            assertTrue(action.problems().isEmpty(), action.name)
            closed(action.parameters.toJson(), action.name)
        }
        val entry = json.getValue("actions").jsonArray.first().jsonObject
        assertEquals(
            setOf("name", "area", "description", "when_to_use", "permission", "requires_confirmation", "undoable", "answers_choice", "effects", "parameters", "returns"),
            entry.keys,
        )
    }

    /** Every object in [schema] refuses unknown properties, and every enum lists values. */
    private fun closed(schema: JsonElement, where: String) {
        if (schema !is JsonObject) return
        if ((schema["type"] as? JsonPrimitive)?.content == "object") {
            assertTrue(schema.getValue("additionalProperties").jsonPrimitive.boolean.not(), where)
        }
        (schema["enum"] as? JsonArray)?.let { assertTrue(it.isNotEmpty(), where) }
        schema.forEach { (key, value) ->
            when (value) {
                is JsonObject -> closed(value, "$where.$key")
                is JsonArray -> value.forEach { closed(it, "$where.$key") }
                is JsonPrimitive -> Unit
                else -> Unit
            }
        }
    }

    private companion object {
        val MOVES = MoveLoader.fromResources()
        val ORACLES = OracleTableLoader.fromResources()

        val PLAYED: Character = run {
            val (_, sheet) = Character.create(3, 2, 2, 1, 1).startTrack(com.irondroid.engine.progress.ProgressTrackType.VOW, "Avenge Kai", com.irondroid.engine.progress.ChallengeRank.DANGEROUS)
            sheet.withDebilities(sheet.debilities.mark(com.irondroid.engine.debility.Debility.SHAKEN))
        }
    }
}
