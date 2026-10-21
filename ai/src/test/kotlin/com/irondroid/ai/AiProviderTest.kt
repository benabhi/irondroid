package com.irondroid.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AiProviderTest {
    private val rollAction = ToolSpec("roll_action", "Rolls an action", buildJsonObject { put("type", "object") })

    @Test
    fun `a whole turn with a tool call runs the same against any provider`() = runTest {
        val provider: AiProvider = ScriptedAiProvider(
            listOf(
                ScriptedTurn.toolCalls(ToolCall("call_1", "roll_action", """{"stat":"edge"}"""), usage = TokenUsage(200, 15)),
                ScriptedTurn.text("You slip past the guard, but your pack snags on the fence.", usage = TokenUsage(260, 40)),
            ),
        )
        val conversation = mutableListOf<ChatMessage>(
            ChatMessage.System("You narrate Ironsworn."),
            ChatMessage.User("I sneak past the guard."),
        )

        // The model asks for a roll; the app runs it and sends the result back.
        val first = provider.chat(ChatRequest("scripted", conversation, listOf(rollAction))).collectReply()
        assertEquals(FinishReason.TOOL_CALLS, first.finishReason)
        conversation += first.toMessage()!!
        conversation += ChatMessage.ToolResult(first.toolCalls.single().id, """{"outcome":"weak_hit"}""")

        // Then it narrates the outcome, streamed.
        val shown = StringBuilder()
        val second = provider.chat(ChatRequest("scripted", conversation, listOf(rollAction))).collectReply { shown.append(it) }
        assertEquals("You slip past the guard, but your pack snags on the fence.", shown.toString())
        assertEquals(FinishReason.STOP, second.finishReason)
        assertEquals(TokenUsage(460, 55), first.usage!! + second.usage!!)
    }

    @Test
    fun `the scripted provider records requests and runs out loudly`() = runTest {
        val provider = ScriptedAiProvider(listOf(ScriptedTurn.text("Hi", chunkSize = 1)))
        val request = ChatRequest("scripted", listOf(ChatMessage.User("Hello")))

        val events = provider.chat(request).toList()
        assertEquals(listOf("H", "i"), events.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
        assertEquals(listOf(request), provider.requests)
        assertEquals(0, provider.remaining)
        assertFailsWith<IllegalStateException> { provider.chat(request).toList() }
        assertFailsWith<IllegalArgumentException> { ScriptedTurn.text("Hi", chunkSize = 0) }
    }

    @Test
    fun `a failing request streams what it had and then throws`() = runTest {
        val error = AiException(AiErrorKind.NETWORK, "Connection lost")
        val provider = ScriptedAiProvider(listOf(ScriptedTurn.failure(error, partialText = "The wind")))
        val shown = StringBuilder()

        val thrown = assertFailsWith<AiException> {
            provider.chat(ChatRequest("scripted", listOf(ChatMessage.User("Go")))).collectReply { shown.append(it) }
        }
        assertSame(error, thrown)
        assertEquals("The wind", shown.toString())
    }

    @Test
    fun `nothing is sent until the answer is collected`() {
        val provider = ScriptedAiProvider(listOf(ScriptedTurn.text("Hi")))
        provider.chat(ChatRequest("scripted", listOf(ChatMessage.User("Hello"))))
        assertTrue(provider.requests.isEmpty())
        assertEquals(1, provider.remaining)
    }

    @Test
    fun `provider info describes its models`() {
        val info = ProviderInfo("deepseek", "DeepSeek", listOf(ModelInfo("deepseek-flash"), ModelInfo("deepseek-v4-pro")))
        assertEquals("deepseek-flash", info.defaultModel.id)
        assertEquals(ModelInfo("deepseek-v4-pro"), info.model("deepseek-v4-pro"))
        assertNull(info.model("gpt"))
        assertFailsWith<IllegalArgumentException> { ProviderInfo("Deep Seek", "DeepSeek", listOf(ModelInfo("m"))) }
        assertFailsWith<IllegalArgumentException> { ProviderInfo("deepseek", " ", listOf(ModelInfo("m"))) }
        assertFailsWith<IllegalArgumentException> { ProviderInfo("deepseek", "DeepSeek", emptyList()) }
        assertFailsWith<IllegalArgumentException> { ProviderInfo("deepseek", "DeepSeek", listOf(ModelInfo("m"), ModelInfo("m"))) }
        assertFailsWith<IllegalArgumentException> { ModelInfo(" ") }
    }

    @Test
    fun `adding a provider is only registering it`() {
        val deepseek = ProviderInfo("deepseek", "DeepSeek", listOf(ModelInfo("deepseek-flash")))
        var createdWith: ProviderSettings? = null
        val registry = ProviderRegistry(
            listOf(
                ProviderRegistration(deepseek) { settings ->
                    createdWith = settings
                    ScriptedAiProvider(emptyList(), deepseek)
                },
                ProviderRegistration(ScriptedAiProvider.INFO) { ScriptedAiProvider(emptyList()) },
            ),
        )

        assertEquals(listOf("deepseek", "scripted"), registry.providers.map { it.id })
        assertEquals(deepseek, registry.find("deepseek"))
        assertNull(registry.find("gemini"))

        val settings = ProviderSettings(apiKey = "sk-secret")
        assertEquals(deepseek, registry.create("deepseek", settings).info)
        assertSame(settings, createdWith)
        assertIs<ScriptedAiProvider>(registry.create("scripted", ProviderSettings()))

        val missingKey = assertFailsWith<AiException> { registry.create("deepseek", ProviderSettings(apiKey = " ")) }
        assertEquals(AiErrorKind.AUTHENTICATION, missingKey.kind)
        assertFailsWith<IllegalArgumentException> { registry.create("gemini", settings) }
        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(List(2) { ProviderRegistration(deepseek) { ScriptedAiProvider(emptyList()) } })
        }
    }

    @Test
    fun `settings never print the API key`() {
        val shown = ProviderSettings(apiKey = "sk-secret", baseUrl = "https://api.deepseek.com").toString()
        assertFalse("sk-secret" in shown)
        assertTrue("***" in shown)
        assertTrue("none" in ProviderSettings().toString())
    }
}
