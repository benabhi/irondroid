package com.irondroid.ai.openai

import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.AiException
import com.irondroid.ai.ChatEvent
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ChatRequest
import com.irondroid.ai.FinishReason
import com.irondroid.ai.ProviderRegistry
import com.irondroid.ai.ProviderSettings
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.TokenUsage
import com.irondroid.ai.ToolCall
import com.irondroid.ai.ToolChoice
import com.irondroid.ai.ToolSpec
import com.irondroid.ai.collectReply
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatibleProvider
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    private val rollAction = ToolSpec(
        "roll_action",
        "Rolls an action",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("stat", buildJsonObject { put("type", "string") }) })
        },
    )
    private val hello = listOf(ChatMessage.System("You narrate Ironsworn."), ChatMessage.User("I sneak past the guard."))

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
        provider = OpenAiCompatibleProvider(DeepSeek.INFO, server.url("/v1/").toString(), "sk-test", client, returnsReasoning = true)
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `text streams piece by piece and the usage comes at the end`() = runTest {
        server.enqueue(
            stream(
                """{"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"The guard is tired."}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"You slip "}}]}""",
                ": keep-alive",
                """{"choices":[{"index":0,"delta":{"content":"past him."},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":30,"prompt_cache_hit_tokens":100}}""",
            ),
        )

        val events = provider.chat(ChatRequest("deepseek-flash", hello)).toList()

        assertEquals(
            listOf(
                ChatEvent.ReasoningDelta("The guard is tired."),
                ChatEvent.TextDelta("You slip "),
                ChatEvent.TextDelta("past him."),
                ChatEvent.Finished(FinishReason.STOP, TokenUsage(120, 30, 100)),
            ),
            events,
        )
        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
        assertEquals("text/event-stream", sent.getHeader("Accept"))
    }

    @Test
    fun `tool calls streamed in pieces arrive whole, before the end`() = runTest {
        server.enqueue(
            stream(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"roll_action","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"stat\":"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"shadow\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":50,"completion_tokens":12,"prompt_tokens_details":{"cached_tokens":40}}}""",
            ),
        )

        val reply = provider.chat(ChatRequest("deepseek-flash", hello, listOf(rollAction))).collectReply()

        assertEquals(listOf(ToolCall("call_1", "roll_action", """{"stat":"shadow"}""")), reply.toolCalls)
        assertEquals(FinishReason.TOOL_CALLS, reply.finishReason)
        assertEquals(TokenUsage(50, 12, 40), reply.usage)
        assertEquals("", reply.text)
    }

    @Test
    fun `the request carries the conversation, the tools and the options in OpenAI's format`() = runTest {
        server.enqueue(stream("""{"choices":[{"index":0,"delta":{"content":"Ok"},"finish_reason":"stop"}]}"""))
        val call = ToolCall("call_1", "roll_action", """{"stat":"shadow"}""")
        val conversation = hello + listOf(
            ChatMessage.Assistant(toolCalls = listOf(call), reasoning = "Sneaking is Shadow."),
            ChatMessage.ToolResult("call_1", """{"outcome":"weak_hit"}"""),
        )
        val request = ChatRequest(
            "deepseek-flash",
            conversation,
            listOf(rollAction),
            ToolChoice.REQUIRED,
            temperature = 0.8,
            maxTokens = 300,
            reasoningEffort = ReasoningEffort.LOW,
        )

        provider.chat(request).collectReply()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

        assertEquals("deepseek-flash", body.string("model"))
        assertEquals("required", body.string("tool_choice"))
        assertEquals("low", body.string("reasoning_effort"))
        assertEquals("0.8", body["temperature"]?.jsonPrimitive?.content)
        assertEquals("300", body["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
        assertEquals("true", body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.content)

        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool.string("type"))
        assertEquals("roll_action", tool["function"]!!.jsonObject.string("name"))
        assertEquals(rollAction.parameters, tool["function"]!!.jsonObject["parameters"])

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.string("role") })
        val assistant = messages[2]
        assertEquals("", assistant.string("content"))
        assertEquals("Sneaking is Shadow.", assistant.string("reasoning_content"))
        val sentCall = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", sentCall.string("id"))
        assertEquals("""{"stat":"shadow"}""", sentCall["function"]!!.jsonObject.string("arguments"))
        assertEquals("call_1", messages[3].string("tool_call_id"))
        assertEquals("""{"outcome":"weak_hit"}""", messages[3].string("content"))
    }

    @Test
    fun `optional fields stay out of the request, and reasoning only goes back where the API wants it`() {
        val plain = OpenAiCompatibleProvider(DeepSeek.INFO, "https://example.com", null)
        val withReasoning = hello + ChatMessage.Assistant("Hi", reasoning = "Thinking")

        val body = plain.requestBody(ChatRequest("m", withReasoning))
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
        assertNull(body["temperature"])
        assertNull(body["max_tokens"])
        assertNull(body["reasoning_effort"])
        assertNull((body["messages"] as JsonArray)[2].jsonObject["reasoning_content"])

        val textOnly = plain.requestBody(ChatRequest("m", hello, toolChoice = ToolChoice.NONE))
        assertEquals("none", textOnly.string("tool_choice"))
    }

    @Test
    fun `no key means no authorization header`() = runTest {
        server.enqueue(stream("""{"choices":[{"index":0,"delta":{"content":"Ok"},"finish_reason":"stop"}]}"""))
        OpenAiCompatibleProvider(DeepSeek.INFO, server.url("/").toString(), null, client).chat(ChatRequest("m", hello)).collectReply()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `HTTP errors become the matching kind of failure, without the key`() = runTest {
        val cases = listOf(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails (sk-test is invalid)"}}""") to AiErrorKind.AUTHENTICATION,
            MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Insufficient Balance"}}""") to AiErrorKind.INSUFFICIENT_BALANCE,
            MockResponse().setResponseCode(429).setHeader("Retry-After", "12").setBody("busy") to AiErrorKind.RATE_LIMITED,
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Model Not Exist"}}""") to AiErrorKind.MODEL_NOT_FOUND,
            MockResponse().setResponseCode(404).setBody("") to AiErrorKind.MODEL_NOT_FOUND,
            MockResponse().setResponseCode(422).setBody("""{"error":"bad tools"}""") to AiErrorKind.INVALID_REQUEST,
            MockResponse().setResponseCode(503).setBody("<html>down</html>") to AiErrorKind.SERVER,
        )
        cases.forEach { server.enqueue(it.first) }

        val failures = cases.map { assertFailsWith<AiException> { provider.chat(ChatRequest("deepseek-flash", hello)).collectReply() } }

        assertEquals(cases.map { it.second }, failures.map { it.kind })
        assertEquals(12, failures[2].retryAfterSeconds)
        assertTrue("Insufficient Balance" in failures[1].message!!)
        assertTrue("bad tools" in failures[5].message!!)
        assertTrue("HTTP 503" in failures[6].message!!)
        assertTrue(failures.none { "Bearer" in it.message!! })
    }

    @Test
    fun `broken streams are reported as such`() = runTest {
        server.enqueue(stream("""{"choices":[{"index":0,"delta":{"content":"Half"}}]}"""))
        server.enqueue(stream("""{"choices":[{"index":0,"delta":{"content":"Half"}}]}""", done = false))
        server.enqueue(stream("not json"))
        server.enqueue(stream("""{"error":{"message":"overloaded"}}"""))
        server.enqueue(stream("""["unexpected"]"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val kinds = List(6) {
            assertFailsWith<AiException> { provider.chat(ChatRequest("deepseek-flash", hello)).collectReply() }.kind
        }

        assertEquals(
            listOf(
                AiErrorKind.MALFORMED_RESPONSE,
                AiErrorKind.MALFORMED_RESPONSE,
                AiErrorKind.MALFORMED_RESPONSE,
                AiErrorKind.SERVER,
                AiErrorKind.MALFORMED_RESPONSE,
                AiErrorKind.MALFORMED_RESPONSE,
            ),
            kinds,
        )
    }

    @Test
    fun `odd finish reasons and fields are tolerated`() = runTest {
        server.enqueue(
            stream(
                """{"choices":[{"index":0,"delta":{"content":null},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{"content":"Cut"},"finish_reason":"length"}],"usage":{"prompt_tokens":5}}""",
            ),
        )
        server.enqueue(stream("""{"choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}"""))
        server.enqueue(stream("""{"choices":[{"index":0,"delta":{},"finish_reason":"insufficient_system_resource"}]}"""))

        val first = provider.chat(ChatRequest("m", hello)).collectReply()
        assertEquals(FinishReason.LENGTH, first.finishReason)
        assertEquals(TokenUsage(5, 0), first.usage)
        assertEquals(FinishReason.CONTENT_FILTER, provider.chat(ChatRequest("m", hello)).collectReply().finishReason)
        assertEquals(FinishReason.OTHER, provider.chat(ChatRequest("m", hello)).collectReply().finishReason)
    }

    @Test
    fun `a dropped connection is a network failure`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val dropped = assertFailsWith<AiException> { provider.chat(ChatRequest("m", hello)).collectReply() }
        assertEquals(AiErrorKind.NETWORK, dropped.kind)
        assertTrue(dropped.retryable)
    }

    @Test
    fun `stopping the collection cancels the request`() = runTest {
        val canceled = CountDownLatch(1)
        val watched = client.newBuilder().eventListener(
            object : EventListener() {
                override fun canceled(call: Call) = canceled.countDown()
            },
        ).build()
        server.enqueue(
            stream(
                """{"choices":[{"index":0,"delta":{"content":"First"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"Second"},"finish_reason":"stop"}]}""",
            ),
        )

        val first = OpenAiCompatibleProvider(DeepSeek.INFO, server.url("/").toString(), "k", watched).chat(ChatRequest("m", hello)).first()

        assertEquals(ChatEvent.TextDelta("First"), first)
        assertTrue(canceled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `DeepSeek is registered with its models and sends reasoning back`() = runTest {
        assertEquals("deepseek-flash", DeepSeek.INFO.defaultModel.id)
        assertTrue(DeepSeek.INFO.models.all { it.supportsTools })
        server.enqueue(stream("""{"choices":[{"index":0,"delta":{"content":"Ok"},"finish_reason":"stop"}]}"""))

        val registry = ProviderRegistry(listOf(DeepSeek.registration(client)))
        val deepseek = registry.create(DeepSeek.ID, ProviderSettings(apiKey = " sk-test ", baseUrl = server.url("/").toString()))
        deepseek.chat(ChatRequest("deepseek-flash", hello + ChatMessage.Assistant("Hi", reasoning = "R") + ChatMessage.User("Go"))).collectReply()

        val sent = server.takeRequest()
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
        val assistant = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject["messages"]!!.jsonArray[2].jsonObject
        assertEquals("R", assistant.string("reasoning_content"))
        assertFalse(DeepSeek.registration().info.needsApiKey.not())
    }

    private fun stream(vararg lines: String, done: Boolean = true): MockResponse {
        val body = buildString {
            lines.forEach { line -> if (line.startsWith(":")) append(line).append("\n\n") else append("data: ").append(line).append("\n\n") }
            if (done) append("data: [DONE]\n\n")
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content
}
