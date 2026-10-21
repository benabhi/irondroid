package com.irondroid.ai.openai

import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.FinishReason
import com.irondroid.ai.ProviderRegistry
import com.irondroid.ai.ProviderSettings
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.ToolSpec
import com.irondroid.ai.collectReply
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
}
