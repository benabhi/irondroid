package com.irondroid.engine.action

import com.irondroid.engine.character.Character
import com.irondroid.engine.character.PendingBonus
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrackType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionHistoryTest {
    /** The game state is one number: health. */
    private var health = 5

    private fun definition(
        name: String,
        permission: ActionPermission,
        effects: Set<ActionEffect> = emptySet(),
        answersChoice: Boolean = false,
        parameters: JsonSchema.ObjectOf = JsonSchema.ObjectOf(),
    ) = ActionDefinition(name, ActionArea.STATE, "Test $name", "A test needs it", permission, ActionReturn("A result"), parameters, effects, answersChoice)

    private val catalog = ActionCatalog.of(
        CatalogVersion(1, 0),
        listOf(
            definition("get_health", ActionPermission.READ),
            definition("hurt", ActionPermission.PLAY, setOf(ActionEffect.CHANGES_CHARACTER), parameters = JsonSchema.obj { integer("amount", "How much", minimum = 1) }),
            definition("ask_oracle", ActionPermission.PLAY, setOf(ActionEffect.ROLLS_DICE)),
            definition("face_danger", ActionPermission.PLAY, setOf(ActionEffect.ROLLS_DICE)),
            definition("choose_option", ActionPermission.PLAY, setOf(ActionEffect.CHANGES_CHARACTER), answersChoice = true, parameters = JsonSchema.obj { string("option", "Option") }),
            definition("edit_health", ActionPermission.SENSITIVE, setOf(ActionEffect.CHANGES_CHARACTER), parameters = JsonSchema.obj { integer("value", "New health", minimum = 0) }),
            definition("undo", ActionPermission.PLAY, setOf(ActionEffect.CHANGES_CHARACTER), answersChoice = true),
        ),
    )

    private lateinit var dispatcher: ActionDispatcher
    private lateinit var history: ActionHistory<Int>

    init {
        dispatcher = ActionDispatcher(
            catalog,
            mapOf(
                "get_health" to ActionHandler { _, _ -> ActionResult("Health $health") },
                "hurt" to ActionHandler { args, _ ->
                    val before = health
                    health -= args.int("amount")
                    ActionResult("Hurt", changes = listOf(StateChange("health", before, health)))
                },
                "ask_oracle" to ActionHandler { _, _ -> ActionResult("Yes") },
                // No damage until the player picks the cost: the move only opens a choice.
                "face_danger" to ActionHandler { _, _ ->
                    ActionResult(
                        "Weak hit",
                        pendingChoice = PendingChoice("Pick a cost", listOf(ChoiceOption("harm", "Lose 1 health"), ChoiceOption("none", "Nothing"))) { picked ->
                            if (picked == listOf("harm")) health -= 1
                            ActionResult("Paid ${picked.single()}")
                        },
                    )
                },
                "choose_option" to ActionHandler { args, call -> dispatcher.choices.decide(dispatcher.choices.current?.id ?: "none", listOf(args.string("option")), call.caller) },
                "edit_health" to ActionHandler { args, _ ->
                    health = args.int("value")
                    ActionResult("Edited")
                },
                "undo" to ActionHandler { _, _ -> ActionResult("Undid ${history.undo().joinToString { it.name }}") },
            ),
        )
        history = ActionHistory(dispatcher.choices, snapshot = { health }, restore = { health = it })
        dispatcher.addListener(history)
    }

    private val logged = mutableListOf<LoggedCall<Int>>()
    private val undone = mutableListOf<List<Int>>()

    private val recorder = object : ActionHistoryListener<Int> {
        override fun onLogged(call: LoggedCall<Int>) {
            logged += call
        }

        override fun onUndone(sequences: List<Int>) {
            undone += sequences
        }
    }

    private fun play(name: String, caller: Caller = Caller.PLAYER, confirmed: Boolean = false, vararg args: Pair<String, Any>) =
        dispatcher.dispatch(
            ActionCall(
                name,
                buildJsonObject {
                    for ((key, value) in args) if (value is Int) put(key, value) else put(key, value.toString())
                },
                caller,
                confirmed,
            ),
        )

    private fun hurt(amount: Int, caller: Caller = Caller.AI) = play("hurt", caller, args = arrayOf("amount" to amount))

    @Test
    fun `every call is logged with who made it, what it asked and what came of it`() {
        history.addListener(recorder)
        play("get_health", Caller.AI)
        hurt(2)
        hurt(0)
        play("edit_health", Caller.AI, args = arrayOf("value" to 4))
        dispatcher.dispatch("hurt", "{oops", Caller.AI)

        assertEquals(listOf(1, 2, 3, 4, 5), history.calls.map { it.sequence })
        assertEquals(history.calls, logged)
        val (read, hit, bad, confirm, broken) = history.calls
        assertEquals(LoggedCall<Int>(1, "get_health", Caller.AI, JsonObject(emptyMap()), CallOutcome.SUCCESS, ActionResult("Health 5").toJson(), UndoRole.NONE), read)

        assertEquals(UndoRole.START, hit.role)
        assertEquals(5, hit.before)
        assertEquals(buildJsonObject { put("amount", 2) }, hit.arguments)
        assertEquals("Hurt", hit.details.getValue("summary").jsonPrimitive.content)

        assertEquals(CallOutcome.FAILURE, bad.outcome)
        assertEquals(UndoRole.NONE, bad.role)
        assertNull(bad.before)
        assertEquals("invalid_arguments", bad.details.getValue("code").jsonPrimitive.content)

        assertEquals(CallOutcome.NEEDS_CONFIRMATION, confirm.outcome)
        assertEquals(JsonObject(emptyMap()), confirm.details)
        assertEquals(CallOutcome.FAILURE, broken.outcome)
        assertEquals(3, health)
    }

    @Test
    fun `undo goes back to before the last play action that changed the game`() {
        hurt(1)
        hurt(2)
        play("get_health")
        play("ask_oracle")
        assertTrue(history.canUndo)

        val result = assertIs<DispatchResult.Success>(play("undo"))
        assertEquals("Undid hurt", result.result.summary)
        assertEquals(4, health)
        assertEquals(listOf(false, true, false, false, false), history.calls.map { it.undone })
        assertEquals(UndoRole.NONE, history.calls.last().role)

        play("undo")
        assertEquals(5, health)
        assertFalse(history.canUndo)
        val nothing = assertIs<DispatchResult.Failure>(play("undo")).error
        assertEquals(ActionError(ActionErrorCode.INVALID_STATE, "There is nothing to undo"), nothing)
    }

    @Test
    fun `a move and the options picked for it are undone together`() {
        history.addListener(recorder)
        hurt(1)
        play("face_danger", Caller.AI)
        val opened = history.calls.last()
        assertEquals(UndoRole.START, opened.role)
        assertTrue(opened.choiceOpenAfter)
        assertEquals(4, opened.before)

        play("get_health", Caller.AI)
        play("choose_option", Caller.PLAYER, args = arrayOf("option" to "harm"))
        assertEquals(UndoRole.PART, history.calls.last().role)
        assertFalse(history.calls.last().choiceOpenAfter)
        assertEquals(3, health)

        play("undo")
        assertEquals(4, health)
        assertEquals(listOf(listOf(2, 4)), undone)
        assertEquals(listOf(false, true, false, true, false), history.calls.map { it.undone })
    }

    @Test
    fun `undo while a choice is open drops the choice`() {
        play("face_danger")
        assertEquals("choice_1", dispatcher.choices.current?.id)
        assertIs<DispatchResult.Success>(play("undo"))
        assertNull(dispatcher.choices.current)
        assertIs<DispatchResult.Success>(play("face_danger"))
    }

    @Test
    fun `sensitive changes are a barrier for undo`() {
        hurt(1)
        play("edit_health", confirmed = true, args = arrayOf("value" to 2))
        assertEquals(UndoRole.BARRIER, history.calls.last().role)
        assertFalse(history.canUndo)
        val error = assertIs<DispatchResult.Failure>(play("undo")).error
        assertEquals("edit_health cannot be undone, nor anything before it", error.message)

        hurt(1)
        play("undo")
        assertEquals(2, health)
        assertFalse(history.canUndo)
    }

    @Test
    fun `a game reopened with a choice open goes back to before the move`() {
        val saved = listOf(
            LoggedCall(1, "hurt", Caller.AI, JsonObject(emptyMap()), CallOutcome.SUCCESS, JsonObject(emptyMap()), UndoRole.START, before = 5),
            LoggedCall(2, "face_danger", Caller.AI, JsonObject(emptyMap()), CallOutcome.SUCCESS, JsonObject(emptyMap()), UndoRole.START, before = 4, choiceOpenAfter = true),
            LoggedCall(3, "get_health", Caller.AI, JsonObject(emptyMap()), CallOutcome.SUCCESS, JsonObject(emptyMap()), UndoRole.NONE, choiceOpenAfter = true),
        )
        health = 1
        history.addListener(recorder)
        history.load(saved.reversed())
        assertEquals(saved, history.calls)

        assertTrue(history.recoverOpenChoice())
        assertEquals(4, health)
        assertEquals(listOf(listOf(2)), undone)
        assertFalse(history.recoverOpenChoice())

        hurt(1)
        assertEquals(4, history.calls.last().sequence)
        history.removeListener(recorder)
        play("undo")
        play("undo")
        assertEquals(5, health)
        assertEquals(1, undone.size)
    }

    @Test
    fun `a closed choice needs no recovery`() {
        assertFalse(history.recoverOpenChoice())
        play("face_danger")
        play("choose_option", args = arrayOf("option" to "none"))
        assertFalse(history.recoverOpenChoice())
        assertEquals(5, health)
    }

    @Test
    fun `listeners hear before and after every call`() {
        val events = mutableListOf<String>()
        dispatcher.addListener(
            object : ActionListener {
                override fun beforeAction(call: ActionCall, action: ActionDefinition) {
                    events += "before ${action.name}"
                }

                override fun afterAction(result: DispatchResult.Success) {
                    events += "after ${result.action.name}"
                }

                override fun onResult(result: DispatchResult) {
                    events += "result ${result::class.simpleName}"
                }
            },
        )
        dispatcher.addListener { }
        play("get_health")
        play("nothing")
        play("edit_health", args = arrayOf("value" to 1))
        assertEquals(
            listOf("before get_health", "after get_health", "result Success", "result Failure", "result NeedsConfirmation"),
            events,
        )
    }

    @Test
    fun `errors turn into json`() {
        assertEquals(
            Json.parseToJsonElement("""{ "code": "rule_violation", "message": "No", "details": ["a"], "suggestion": "Try" }"""),
            ActionError(ActionErrorCode.RULE_VIOLATION, "No", listOf("a"), "Try").toJson(),
        )
        assertEquals(JsonPrimitive("x"), ActionError(ActionErrorCode.INTERNAL, "x").toJson().getValue("message"))
        assertFalse("suggestion" in ActionError(ActionErrorCode.INTERNAL, "x").toJson())
    }

    @Test
    fun `a character sheet survives a trip through json`() {
        val (trackId, withTrack) = Character.create(3, 2, 2, 1, 1).startTrack(ProgressTrackType.VOW, "Avenge Kai", ChallengeRank.DANGEROUS)
        val sheet = withTrack
            .copy(experience = 3, pendingBonuses = listOf(PendingBonus(1, "face_danger")))
            .withDebilities(withTrack.debilities.mark(Debility.WOUNDED))
        val text = Json.encodeToString(Character.serializer(), sheet)
        assertEquals(sheet, Json.decodeFromString(Character.serializer(), text))
        assertEquals("vow_1", trackId)
    }
}
