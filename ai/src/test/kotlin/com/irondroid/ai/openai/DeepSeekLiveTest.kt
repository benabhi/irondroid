package com.irondroid.ai.openai

import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ConnectionCheck
import com.irondroid.ai.ConnectionResult
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.FinishReason
import com.irondroid.ai.ProviderRegistry
import com.irondroid.ai.ProviderSettings
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.ToolSpec
import com.irondroid.ai.collectReply
import com.irondroid.ai.tools.ActionTools
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.game.GameAccess
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real DeepSeek API with the key of the environment (never stored in the project):
 * `.\gradlew.bat :ai:liveTest`. Skipped when DEEPSEEK_API_KEY is not set.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekLiveTest {
    private val deepseek = ProviderRegistry(listOf(DeepSeek.registration()))
        .create(DeepSeek.ID, ProviderSettings(apiKey = System.getenv("DEEPSEEK_API_KEY")))

    private val narrator = ChatMessage.System(
        "You narrate a dark fantasy solo game in neutral Latin American Spanish, in second person, in 2 to 4 sentences.",
    )

    @Test
    fun `the connection check passes with both models`() = runBlocking {
        DeepSeek.INFO.models.forEach { model ->
            val result = ConnectionCheck.run(deepseek, model.id)
            println("Connection check ${model.id}: $result")
            assertIs<ConnectionResult.Ready>(result)
        }
    }

    @Test
    fun `a wrong key is reported as such`() = runBlocking {
        val wrong = ProviderRegistry(listOf(DeepSeek.registration())).create(DeepSeek.ID, ProviderSettings(apiKey = "sk-wrong"))
        val result = assertIs<ConnectionResult.Failed>(ConnectionCheck.run(wrong, "deepseek-flash"))
        println("Wrong key: ${result.error.message}")
        assertEquals(AiErrorKind.AUTHENTICATION, result.error.kind)
    }

    @Test
    fun `narrates in streaming`() = runBlocking {
        val started = System.nanoTime()
        var firstText = 0L
        val reply = deepseek.chat(
            ChatRequest("deepseek-flash", listOf(narrator, ChatMessage.User("Entro a la cabaña abandonada.")), reasoningEffort = ReasoningEffort.LOW),
        ).collectReply { if (firstText == 0L) firstText = System.nanoTime() }

        println("Narration: ${reply.text}")
        println("First text after ${(firstText - started) / 1_000_000} ms, total ${(System.nanoTime() - started) / 1_000_000} ms, usage ${reply.usage}")
        assertNotNull(reply.usage)
        assertEquals(FinishReason.STOP, reply.finishReason)
        assertTrue(reply.text.isNotBlank())
    }

    @Test
    fun `calls a tool and narrates its result`() = runBlocking {
        val askOracle = ToolSpec(
            "ask_oracle",
            "Asks the yes/no oracle a question about the fiction. Use it when the answer is uncertain.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("question") { put("type", "string"); put("description", "The yes/no question") }
                    putJsonObject("odds") {
                        put("type", "string")
                        putJsonArray("enum") { listOf("almost_certain", "likely", "50_50", "unlikely", "small_chance").forEach { add(it) } }
                    }
                }
                putJsonArray("required") { add("question"); add("odds") }
                put("additionalProperties", false)
            },
        )
        val conversation = mutableListOf<ChatMessage>(
            narrator,
            ChatMessage.User("¿Hay alguien escondido en la cabaña? Consultá el oráculo, es probable."),
        )

        val ask = deepseek.chat(ChatRequest("deepseek-flash", conversation, listOf(askOracle), reasoningEffort = ReasoningEffort.LOW)).collectReply()
        println("Tool calls: ${ask.toolCalls}")
        assertEquals(FinishReason.TOOL_CALLS, ask.finishReason)
        val call = ask.toolCalls.single { it.name == "ask_oracle" }
        assertEquals(JsonPrimitive("likely"), call.parsedArguments()?.get("odds"))

        conversation += ask.toMessage()!!
        conversation += ChatMessage.ToolResult(call.id, """{"answer":"yes","roll":42,"extreme":false}""")
        val narration = deepseek.chat(ChatRequest("deepseek-flash", conversation, listOf(askOracle), reasoningEffort = ReasoningEffort.LOW)).collectReply()
        println("Narration: ${narration.text}")
        assertEquals(FinishReason.STOP, narration.finishReason)
        assertTrue(narration.text.isNotBlank())
    }

    @Test
    fun `plays a move with the real action catalog`() = runBlocking {
        val game = object : GameAccess {
            override var sheet = Character.create(edge = 2, heart = 1, iron = 3, shadow = 2, wits = 1)
            override val characterName = "Kira"
            override val history: ActionHistory<Character>? = null
            override fun update(change: (Character) -> Character) {
                sheet = change(sheet)
            }

            override fun save() {}
        }
        val tools = ActionTools(GameActions(game, MoveLoader.fromResources(), OracleTableLoader.fromResources(), SeededDice.fromSeed(3), "es").dispatcher())
        val conversation = mutableListOf<ChatMessage>(
            ChatMessage.System(
                "You narrate Ironsworn in neutral Latin American Spanish, in second person, in 2 to 4 sentences. " +
                    "When the player's action is risky, roll the move that fits with the tools; never invent a roll. " +
                    "After the roll, narrate the outcome without numbers.",
            ),
            ChatMessage.User("Me escabullo entre las sombras para pasar junto al guardia dormido sin despertarlo."),
        )
        val called = mutableListOf<String>()
        var narration = ""
        for (step in 1..6) {
            val reply = deepseek.chat(ChatRequest("deepseek-flash", conversation, tools.specs, reasoningEffort = ReasoningEffort.LOW)).collectReply()
            conversation += reply.toMessage() ?: break
            if (reply.toolCalls.isEmpty()) {
                narration = reply.text
                break
            }
            reply.toolCalls.forEach { call ->
                val run = tools.run(call)
                called += call.name + " " + call.arguments
                println("Tool: ${call.name} ${call.arguments} -> ${run.message.content.take(300)}")
                conversation += run.message
            }
        }
        println("Narration: $narration")
        assertTrue(called.any { it.startsWith("roll_move") }, "expected a move roll, got $called")
        assertTrue(narration.isNotBlank())
    }
}
