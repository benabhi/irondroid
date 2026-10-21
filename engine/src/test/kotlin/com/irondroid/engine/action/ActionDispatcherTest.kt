package com.irondroid.engine.action

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionDispatcherTest {
    /** A tiny game: one progress track that the actions read and change. */
    private var progress = 0
    private var tracks = mutableSetOf("vow")

    private val getProgress = ActionDefinition(
        name = "get_progress",
        area = ActionArea.QUERY,
        description = "Reads the progress",
        whenToUse = "You need the progress",
        permission = ActionPermission.READ,
        returns = ActionReturn("The progress"),
    )

    private val markProgress = ActionDefinition(
        name = "mark_progress",
        area = ActionArea.PROGRESS,
        description = "Marks progress",
        whenToUse = "The fiction moves forward",
        permission = ActionPermission.PLAY,
        returns = ActionReturn("The new progress"),
        parameters = JsonSchema.obj {
            string("track", "Track name")
            integer("times", "How many times", required = false, minimum = 1, maximum = 4)
        },
        effects = setOf(ActionEffect.CHANGES_TRACKS),
    )

    private val deleteTrack = ActionDefinition(
        name = "delete_track",
        area = ActionArea.PROGRESS,
        description = "Deletes a track",
        whenToUse = "The player asks for it",
        permission = ActionPermission.SENSITIVE,
        returns = ActionReturn("Nothing"),
        parameters = JsonSchema.obj { string("track", "Track name") },
        effects = setOf(ActionEffect.DELETES_DATA),
    )

    private val catalog = ActionCatalog.of(CatalogVersion(1, 0), listOf(getProgress, markProgress, deleteTrack))

    private val handlers = mapOf(
        "get_progress" to ActionHandler { _, _ -> buildJsonObject { put("progress", progress) } },
        "mark_progress" to ActionHandler { args, _ ->
            val track = args.string("track")
            if (track !in tracks) throw ActionRejectedException.state("There is no track called $track", "Start it with swear_an_iron_vow")
            if (progress >= 10) throw ActionRejectedException.rule("The track is full")
            progress = minOf(10, progress + (args.intOrNull("times") ?: 1))
            buildJsonObject { put("progress", progress) }
        },
        "delete_track" to ActionHandler { args, _ ->
            if (!tracks.remove(args.string("track"))) error("track vanished")
            JsonObject(emptyMap())
        },
    )

    private val dispatcher = ActionDispatcher(catalog, handlers)

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                is Int -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    @Test
    fun `a valid call runs its handler and returns its data`() {
        val result = assertIs<DispatchResult.Success>(dispatcher.dispatch(ActionCall("mark_progress", args("track" to "vow", "times" to 3), Caller.AI)))
        assertEquals(markProgress, result.action)
        assertEquals(buildJsonObject { put("progress", 3) }, result.data)
        assertEquals(3, progress)
    }

    @Test
    fun `an unknown action fails and suggests the closest name`() {
        val typo = assertIs<DispatchResult.Failure>(dispatcher.dispatch(ActionCall("mark_progres", caller = Caller.AI))).error
        assertEquals(ActionErrorCode.UNKNOWN_ACTION, typo.code)
        assertEquals("There is no action called mark_progres", typo.message)
        assertEquals("Did you mean mark_progress?", typo.suggestion)

        val far = assertIs<DispatchResult.Failure>(dispatcher.dispatch(ActionCall("summon_dragon", caller = Caller.AI))).error
        assertNull(far.suggestion)
    }

    @Test
    fun `invalid arguments fail with every problem and the handler does not run`() {
        val result = dispatcher.dispatch(ActionCall("mark_progress", args("times" to 9, "speed" to 1), Caller.AI))
        val error = assertIs<DispatchResult.Failure>(result).error
        assertEquals(ActionErrorCode.INVALID_ARGUMENTS, error.code)
        assertEquals("The arguments of mark_progress are not valid", error.message)
        assertEquals(listOf("speed: unknown parameter", "track: required", "times: must be at most 4, was 9"), error.details)
        assertEquals(0, progress)
    }

    @Test
    fun `handlers refuse calls the rules or the state do not allow`() {
        val missing = assertIs<DispatchResult.Failure>(dispatcher.dispatch(ActionCall("mark_progress", args("track" to "journey"), Caller.AI))).error
        assertEquals(ActionError(ActionErrorCode.INVALID_STATE, "There is no track called journey", suggestion = "Start it with swear_an_iron_vow"), missing)

        progress = 10
        val full = assertIs<DispatchResult.Failure>(dispatcher.dispatch(ActionCall("mark_progress", args("track" to "vow"), Caller.PLAYER))).error
        assertEquals(ActionError(ActionErrorCode.RULE_VIOLATION, "The track is full"), full)
    }

    @Test
    fun `a sensitive action waits for the player's confirmation`() {
        val call = ActionCall("delete_track", args("track" to "vow"), Caller.AI)
        val waiting = assertIs<DispatchResult.NeedsConfirmation>(dispatcher.dispatch(call))
        assertEquals(deleteTrack, waiting.action)
        assertEquals(setOf("vow"), tracks)

        assertIs<DispatchResult.Success>(dispatcher.dispatch(call.copy(confirmed = true)))
        assertEquals(emptySet(), tracks)
    }

    @Test
    fun `a handler that crashes gives an internal error, not a crash`() {
        val error = assertIs<DispatchResult.Failure>(
            dispatcher.dispatch(ActionCall("delete_track", args("track" to "journey"), Caller.PLAYER, confirmed = true)),
        ).error
        assertEquals(ActionErrorCode.INTERNAL, error.code)
        assertEquals("delete_track failed inside the app: track vanished", error.message)

        val silent = ActionDispatcher(catalog, handlers + ("get_progress" to ActionHandler { _, _ -> throw IllegalStateException() }))
        val unnamed = assertIs<DispatchResult.Failure>(silent.dispatch(ActionCall("get_progress", caller = Caller.AI))).error
        assertEquals("get_progress failed inside the app: IllegalStateException", unnamed.message)
    }

    @Test
    fun `listeners hear every success with whether it changed the game`() {
        val heard = mutableListOf<Pair<String, Boolean>>()
        val listener = ActionListener { heard += it.action.name to it.action.changesState }
        dispatcher.addListener(listener)
        dispatcher.addListener { error("a broken listener does not stop the others") }

        dispatcher.dispatch(ActionCall("get_progress", caller = Caller.AI))
        dispatcher.dispatch(ActionCall("mark_progress", args("track" to "vow"), Caller.PLAYER))
        dispatcher.dispatch(ActionCall("mark_progress", args("track" to "lost"), Caller.PLAYER))
        dispatcher.dispatch(ActionCall("delete_track", args("track" to "vow"), Caller.PLAYER))
        assertEquals(listOf("get_progress" to false, "mark_progress" to true), heard)

        dispatcher.removeListener(listener)
        dispatcher.dispatch(ActionCall("get_progress", caller = Caller.AI))
        assertEquals(2, heard.size)
    }

    @Test
    fun `json text arguments are parsed, and bad text is an argument error`() {
        assertIs<DispatchResult.Success>(dispatcher.dispatch("mark_progress", """{"track":"vow","times":2}""", Caller.AI))
        assertIs<DispatchResult.Success>(dispatcher.dispatch("get_progress", "  ", Caller.AI))
        assertIs<DispatchResult.NeedsConfirmation>(dispatcher.dispatch("delete_track", """{"track":"vow"}""", Caller.AI))
        assertIs<DispatchResult.Success>(dispatcher.dispatch("delete_track", """{"track":"vow"}""", Caller.PLAYER, confirmed = true))
        assertEquals(2, progress)

        for (bad in listOf("{track: vow", "[1, 2]")) {
            val failure = assertIs<DispatchResult.Failure>(dispatcher.dispatch("mark_progress", bad, Caller.AI))
            assertEquals(ActionErrorCode.INVALID_ARGUMENTS, failure.error.code)
            assertEquals("The arguments of mark_progress must be a JSON object", failure.error.message)
            assertEquals(listOf("received: $bad"), failure.error.details)
            assertEquals(Caller.AI, failure.call.caller)
        }
    }

    @Test
    fun `every action needs exactly one handler`() {
        val errors = assertFailsWith<ActionCatalogException> {
            ActionDispatcher(catalog, handlers - "get_progress" + ("roll_dice" to ActionHandler { _, _ -> JsonObject(emptyMap()) }))
        }.errors
        assertEquals(listOf("Action get_progress has no handler", "Handler roll_dice has no action in the catalog"), errors)
    }

    @Test
    fun `arguments give typed values and drop nulls`() {
        val arguments = ActionArguments(
            buildJsonObject {
                put("name", "Kai")
                put("level", 3)
                put("weight", 1.5)
                put("brave", true)
                put("gone", kotlinx.serialization.json.JsonNull)
                put("tags", buildJsonArray { add(JsonPrimitive(1)) })
                put("target", buildJsonObject { put("who", "Ash") })
            },
        )
        assertEquals("Kai", arguments.string("name"))
        assertEquals("Kai", arguments.stringOrNull("name"))
        assertNull(arguments.stringOrNull("gone"))
        assertFalse(arguments.has("gone"))
        assertTrue(arguments.has("level"))
        assertEquals(3, arguments.int("level"))
        assertEquals(3, arguments.intOrNull("level"))
        assertNull(arguments.intOrNull("missing"))
        assertEquals(1.5, arguments.double("weight"))
        assertTrue(arguments.boolean("brave"))
        assertEquals(true, arguments.booleanOrNull("brave"))
        assertNull(arguments.booleanOrNull("missing"))
        assertEquals(1, arguments.array("tags").size)
        assertEquals("Ash", arguments.obj("target").string("who"))
        assertFailsWith<IllegalArgumentException> { arguments.string("missing") }

        assertEquals(ActionArguments(buildJsonObject { put("x", 1) }), ActionArguments(buildJsonObject { put("x", 1) }))
        assertEquals(buildJsonObject { put("x", 1) }.hashCode(), ActionArguments(buildJsonObject { put("x", 1) }).hashCode())
        assertEquals("{}", ActionArguments.NONE.toString())
        assertFalse(ActionArguments.NONE.equals("{}"))
    }
}
