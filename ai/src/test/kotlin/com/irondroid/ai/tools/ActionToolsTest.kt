package com.irondroid.ai.tools

import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.ToolCall
import com.irondroid.ai.openai.DeepSeek
import com.irondroid.ai.openai.OpenAiCompatibleProvider
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.game.GameAccess
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionToolsTest {
    /** One open game with a fixed character, enough for the real MVP catalog to run. */
    private class OpenGame : GameAccess {
        override var sheet: Character = Character.create(edge = 2, heart = 1, iron = 3, shadow = 2, wits = 1)
        override val characterName = "Kira"
        override val history: ActionHistory<Character>? = null
        override fun update(change: (Character) -> Character) {
            sheet = change(sheet)
        }

        override fun save() {}
    }

    private val game = OpenGame()
    private val actions = GameActions(game, MoveLoader.fromResources(), OracleTableLoader.fromResources(), SeededDice.fromSeed(7), "es")
    private val tools = ActionTools(actions.dispatcher())

    @Test
    fun `every action of the catalog becomes a tool, except the player's own`() {
        val catalog = actions.dispatcher().catalog.actions
        assertEquals(catalog.map { it.name } - "choose_option", tools.specs.map { it.name })
        tools.specs.zip(tools.actions).forEach { (spec, action) ->
            assertEquals(action.fullDescription, spec.description)
            assertEquals(action.parameters.toJson(), spec.parameters)
            assertEquals(JsonPrimitive("object"), spec.parameters["type"])
        }
        assertTrue(tools.specs.none { it.name == "choose_option" })
        assertEquals(catalog.size, ActionTools(actions.dispatcher(), hidden = emptySet()).specs.size)
    }

    @Test
    fun `the tools go into a provider request in its own format`() {
        val provider = OpenAiCompatibleProvider(DeepSeek.INFO, "https://example.com", null)
        val body = provider.requestBody(ChatRequest("deepseek-flash", listOf(ChatMessage.User("Hola")), tools.specs))
        val sent = body["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject }
        assertEquals(tools.specs.map { it.name }, sent.map { it["name"]!!.jsonPrimitive.content })
        // The whole catalog stays small enough to send in every turn (the prompt cache absorbs it).
        val size = body["tools"].toString().length
        println("Tools JSON: ${tools.specs.size} tools, $size characters")
        assertTrue(size < 60_000)
    }

    @Test
    fun `a good call runs through the dispatcher as the AI and returns the structured result`() {
        val run = tools.run(ToolCall("call_1", "ask_oracle", """{"question":"¿Hay alguien en la cabaña?","odds":"likely"}"""))

        val success = assertIs<DispatchResult.Success>(run.result)
        assertEquals(com.irondroid.engine.action.Caller.AI, success.call.caller)
        assertEquals("call_1", run.message.callId)
        val output = parse(run.message.content)
        assertEquals("ok", output.string("status"))
        assertEquals("likely", output["data"]!!.jsonObject.string("odds"))
        assertTrue(output.string("summary")!!.isNotBlank())
    }

    @Test
    fun `a call that changes the game changes it, as from the screens`() {
        val before = game.sheet.momentum.value
        val run = tools.run(ToolCall("call_2", "adjust_meter", """{"meter":"momentum","amount":1}"""))
        assertIs<DispatchResult.Success>(run.result, run.message.content)
        assertEquals(before + 1, game.sheet.momentum.value)
    }

    @Test
    fun `bad calls come back as explained errors the model can read`() {
        val cases = mapOf(
            ToolCall("c1", "roll_dice", "{}") to "unknown_action",
            ToolCall("c2", "ask_oracle", """{"question":"¿Llueve?","odds":"sure"}""") to "invalid_arguments",
            ToolCall("c3", "ask_oracle", "not json") to "invalid_arguments",
            ToolCall("c4", "choose_option", """{"choice_id":"x","option_ids":["a"]}""") to null,
        )
        cases.forEach { (call, code) ->
            val run = tools.run(call)
            assertIs<DispatchResult.Failure>(run.result)
            val output = parse(run.message.content)
            assertEquals("error", output.string("status"))
            val error = output["error"]!!.jsonObject
            if (code != null) assertEquals(code, error.string("code"))
            assertTrue(error.string("message")!!.isNotBlank())
        }
    }

    @Test
    fun `sensitive actions wait for the player and say so`() {
        val before = game.sheet
        val run = tools.run(ToolCall("c5", "edit_character", """{"health":1}"""))

        assertIs<DispatchResult.NeedsConfirmation>(run.result)
        val output = parse(run.message.content)
        assertEquals("needs_confirmation", output.string("status"))
        assertTrue("NOT run" in output.string("message")!!)
        assertEquals(before, game.sheet)
    }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content
}
