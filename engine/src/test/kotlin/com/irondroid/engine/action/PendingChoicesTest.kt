package com.irondroid.engine.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingChoicesTest {
    /** A tiny game: Face Danger on a weak hit makes the player pick a cost, and losing supply may ask once more. */
    private var health = 5
    private var supply = 3
    private var momentum = 2

    private fun definition(name: String, permission: ActionPermission, answersChoice: Boolean = false, effects: Set<ActionEffect> = emptySet()) =
        ActionDefinition(
            name = name,
            area = ActionArea.MOVES,
            description = "Test $name",
            whenToUse = "A test needs it",
            permission = permission,
            returns = ActionReturn("A result"),
            parameters = if (answersChoice) {
                JsonSchema.obj {
                    string("choice", "Choice id")
                    array("options", "Option ids", JsonSchema.StringValue("Option id"))
                    string("why", "Why", required = false)
                }
            } else {
                JsonSchema.ObjectOf()
            },
            effects = effects,
            answersChoice = answersChoice,
        )

    private val faceDanger = definition("face_danger", ActionPermission.PLAY, effects = setOf(ActionEffect.ROLLS_DICE, ActionEffect.CHANGES_CHARACTER))
    private val getSheet = definition("get_sheet", ActionPermission.READ)
    private val suggestOption = definition("suggest_option", ActionPermission.PLAY, answersChoice = true)
    private val chooseOption = definition("choose_option", ActionPermission.PLAY, answersChoice = true, effects = setOf(ActionEffect.CHANGES_CHARACTER))
    private val askAgain = definition("ask_again", ActionPermission.PLAY, answersChoice = true)
    private val brokenChoice = definition("broken_choice", ActionPermission.PLAY, effects = setOf(ActionEffect.ROLLS_DICE))

    private fun costChoice() = PendingChoice(
        prompt = "Pick the cost of the weak hit",
        options = listOf(ChoiceOption("lose_momentum", "Suffer -1 momentum"), ChoiceOption("lose_supply", "Suffer -1 supply")),
    ) { picked ->
        if (picked == listOf("lose_momentum")) {
            momentum -= 1
            ActionResult("Lost 1 momentum", changes = listOf(StateChange("momentum", momentum + 1, momentum)))
        } else {
            supply -= 1
            // Running out of supply asks one more thing, as a move with two choices in a row would.
            ActionResult(
                "Lost 1 supply",
                changes = listOf(StateChange("supply", supply + 1, supply)),
                pendingChoice = PendingChoice("Pick two to keep", listOf(ChoiceOption("a", "A"), ChoiceOption("b", "B"), ChoiceOption("c", "C")), count = 2) {
                    ActionResult("Kept ${it.joinToString(" and ")}")
                },
            )
        }
    }

    private val catalog = ActionCatalog.of(CatalogVersion(1, 0), listOf(faceDanger, getSheet, suggestOption, chooseOption, askAgain, brokenChoice))

    private lateinit var dispatcher: ActionDispatcher

    private val handlers = mapOf(
        "face_danger" to ActionHandler { _, _ ->
            ActionResult(
                "Weak hit: you make it, at a cost",
                buildJsonObject {
                    put("outcome", "weak_hit")
                    put("action_die", 4)
                },
                pendingChoice = costChoice(),
            )
        },
        "get_sheet" to ActionHandler { _, _ -> ActionResult("Health $health, supply $supply, momentum $momentum") },
        "suggest_option" to ActionHandler { args, _ ->
            dispatcher.choices.suggest(args.string("choice"), args.array("options").map { it.jsonPrimitive.content }, args.stringOrNull("why") ?: "")
            ActionResult("Suggestion marked")
        },
        "choose_option" to ActionHandler { args, call ->
            dispatcher.choices.decide(args.string("choice"), args.array("options").map { it.jsonPrimitive.content }, call.caller)
        },
        "ask_again" to ActionHandler { _, _ -> ActionResult("Asks again", pendingChoice = costChoice()) },
        "broken_choice" to ActionHandler { _, _ ->
            ActionResult("Breaks", pendingChoice = PendingChoice("Boom", listOf(ChoiceOption("x", "X"))) { error("resume failed") })
        },
    )

    init {
        dispatcher = ActionDispatcher(catalog, handlers)
    }

    private fun call(name: String, caller: Caller = Caller.PLAYER, choice: String? = null, vararg options: String, why: String? = null) =
        dispatcher.dispatch(
            ActionCall(
                name,
                buildJsonObject {
                    choice?.let { put("choice", it) }
                    if (choice != null) put("options", kotlinx.serialization.json.JsonArray(options.map(::JsonPrimitive)))
                    why?.let { put("why", it) }
                },
                caller,
            ),
        )

    private fun errorOf(result: DispatchResult) = assertIs<DispatchResult.Failure>(result).error

    @Test
    fun `a result with a choice opens it and blocks other play actions until the player decides`() {
        val rolled = assertIs<DispatchResult.Success>(call("face_danger", Caller.AI))
        val choice = assertNotNull(rolled.result.pendingChoice)
        assertEquals("choice_1", choice.id)
        assertEquals(choice, dispatcher.choices.current)

        val blocked = errorOf(call("face_danger", Caller.AI))
        assertEquals(ActionErrorCode.INVALID_STATE, blocked.code)
        assertEquals("The player has choice choice_1 open and must decide it first", blocked.message)
        assertEquals("Wait for the player; you can mark your suggestion with suggest_option", blocked.suggestion)
        assertIs<DispatchResult.Success>(call("get_sheet", Caller.AI))

        assertIs<DispatchResult.Success>(call("suggest_option", Caller.AI, "choice_1", "lose_momentum", why = "Supply is low"))
        assertEquals(Suggestion(listOf("lose_momentum"), "Supply is low"), choice.suggestion)

        val decided = assertIs<DispatchResult.Success>(call("choose_option", Caller.PLAYER, "choice_1", "lose_momentum"))
        assertEquals(ActionResult("Lost 1 momentum", changes = listOf(StateChange("momentum", 2, 1))), decided.result)
        assertNull(dispatcher.choices.current)
        assertIs<DispatchResult.Success>(call("face_danger"))
    }

    @Test
    fun `deciding can open the next choice with a new id`() {
        call("face_danger")
        val next = assertNotNull(assertIs<DispatchResult.Success>(call("choose_option", Caller.PLAYER, "choice_1", "lose_supply")).result.pendingChoice)
        assertEquals("choice_2", next.id)
        assertEquals(2, supply)

        assertEquals(
            "Choice choice_2 takes exactly 2 options, got 1",
            errorOf(call("choose_option", Caller.PLAYER, "choice_2", "a")).message,
        )
        assertIs<DispatchResult.Success>(call("suggest_option", Caller.AI, "choice_2", "c", why = "C fits"))
        assertEquals("Kept a and c", assertIs<DispatchResult.Success>(call("choose_option", Caller.PLAYER, "choice_2", "a", "c")).result.summary)
        assertNull(dispatcher.choices.current)
    }

    @Test
    fun `only the player decides and only valid options count`() {
        call("face_danger")
        val ai = errorOf(call("choose_option", Caller.AI, "choice_1", "lose_momentum"))
        assertEquals(ActionError(ActionErrorCode.RULE_VIOLATION, "Only the player decides a choice", suggestion = "Mark your suggestion with suggest_option and let the player pick"), ai)

        assertEquals(
            ActionError(ActionErrorCode.RULE_VIOLATION, "Choice choice_1 has no option fight, flee", suggestion = "Pick among lose_momentum, lose_supply"),
            errorOf(call("choose_option", Caller.PLAYER, "choice_1", "fight", "flee")),
        )
        assertEquals("An option is picked twice", errorOf(call("choose_option", Caller.PLAYER, "choice_1", "lose_supply", "lose_supply")).message)
        assertEquals("Choice choice_1 takes exactly 1 options, got 2", errorOf(call("choose_option", Caller.PLAYER, "choice_1", "lose_supply", "lose_momentum")).message)
        assertEquals("Choice choice_1 takes 1 to 1 options, got 0", errorOf(call("suggest_option", Caller.AI, "choice_1")).message)
        assertEquals("Say why you suggest it", errorOf(call("suggest_option", Caller.AI, "choice_1", "lose_supply", why = " ")).message)

        val wrongId = errorOf(call("choose_option", Caller.PLAYER, "choice_9", "lose_supply"))
        assertEquals(ActionError(ActionErrorCode.INVALID_STATE, "Choice choice_9 is not open", suggestion = "The open choice is choice_1"), wrongId)
        assertEquals(3, supply)
        assertEquals(2, momentum)
        assertNotNull(dispatcher.choices.current)
    }

    @Test
    fun `there is nothing to decide without an open choice`() {
        assertEquals(
            ActionError(ActionErrorCode.INVALID_STATE, "There is no choice to make"),
            errorOf(call("choose_option", Caller.PLAYER, "choice_1", "a")),
        )
    }

    @Test
    fun `a failing resume keeps the choice open so the player can decide again`() {
        call("broken_choice")
        val error = errorOf(call("choose_option", Caller.PLAYER, "choice_1", "x"))
        assertEquals(ActionErrorCode.INTERNAL, error.code)
        assertEquals("choice_1", dispatcher.choices.current?.id)
    }

    @Test
    fun `opening a choice while another is open is an internal error`() {
        call("face_danger")
        val error = errorOf(call("ask_again", Caller.AI, "choice_1", "lose_supply"))
        assertEquals(ActionError(ActionErrorCode.INTERNAL, "ask_again opened a choice while choice_1 is still open"), error)
        assertEquals("choice_1", dispatcher.choices.current?.id)
        assertFailsWith<IllegalStateException> { PendingChoices().apply { open(costChoice()); open(costChoice()) } }
    }

    @Test
    fun `a choice needs unique options and a count it can meet`() {
        assertFailsWith<IllegalArgumentException> { PendingChoice("p", emptyList()) { ActionResult("") } }
        assertFailsWith<IllegalArgumentException> { PendingChoice("p", listOf(ChoiceOption("a", "A"), ChoiceOption("a", "B"))) { ActionResult("") } }
        assertFailsWith<IllegalArgumentException> { PendingChoice("p", listOf(ChoiceOption("a", "A")), count = 2) { ActionResult("") } }
        assertFailsWith<IllegalArgumentException> { PendingChoice("p", listOf(ChoiceOption("a", "A")), count = 0) { ActionResult("") } }
    }

    @Test
    fun `results and choices turn into json for the AI`() {
        val rolled = assertIs<DispatchResult.Success>(call("face_danger"))
        assertEquals(
            Json.parseToJsonElement(
                """
                { "summary": "Weak hit: you make it, at a cost",
                  "data": { "outcome": "weak_hit", "action_die": 4 },
                  "changes": [],
                  "pending_choice": { "id": "choice_1", "prompt": "Pick the cost of the weak hit", "count": 1,
                    "options": [ { "id": "lose_momentum", "text": "Suffer -1 momentum" }, { "id": "lose_supply", "text": "Suffer -1 supply" } ],
                    "suggestion": null } }
                """,
            ),
            rolled.result.toJson(),
        )
        dispatcher.choices.suggest("choice_1", listOf("lose_supply"), "Momentum matters more")
        val suggestion = assertNotNull(dispatcher.choices.current).toJson().getValue("suggestion")
        assertEquals(Json.parseToJsonElement("""{ "option_ids": ["lose_supply"], "why": "Momentum matters more" }"""), suggestion)

        val changes = ActionResult(
            "Changed",
            changes = listOf(StateChange("health", 5, 3), StateChange("name", null, "Kai"), StateChange("wounded", false, true)),
        ).toJson()
        assertEquals(JsonNull, changes.getValue("pending_choice"))
        assertEquals(
            Json.parseToJsonElement(
                """[ { "field": "health", "before": 5, "after": 3 }, { "field": "name", "before": null, "after": "Kai" },
                     { "field": "wounded", "before": false, "after": true } ]""",
            ),
            changes.getValue("changes"),
        )
        assertEquals(3, changes.getValue("changes").jsonArray.size)
    }

    @Test
    fun `actions that answer a choice are declared as such`() {
        assertTrue(suggestOption.problems().isEmpty())
        assertEquals(
            listOf("Action peek: a read action cannot answer a choice"),
            getSheet.copy(name = "peek", answersChoice = true).problems(),
        )
        assertEquals(
            "Test choose_option. Use it when a test needs it. Effects: changes the character sheet. " +
                "It works on the choice the player has open. Returns: a result.",
            chooseOption.fullDescription,
        )
        assertEquals("true", chooseOption.toJson().getValue("answers_choice").jsonPrimitive.content)
    }
}
