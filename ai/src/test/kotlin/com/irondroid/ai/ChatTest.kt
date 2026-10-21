package com.irondroid.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatTest {
    private val schema = buildJsonObject { put("type", "object") }
    private val call = ToolCall("call_1", "roll_action", """{"stat":"edge"}""")

    @Test
    fun `messages refuse to be empty`() {
        assertFailsWith<IllegalArgumentException> { ChatMessage.System(" ") }
        assertFailsWith<IllegalArgumentException> { ChatMessage.User("") }
        assertFailsWith<IllegalArgumentException> { ChatMessage.Assistant() }
        assertFailsWith<IllegalArgumentException> { ChatMessage.Assistant(text = " ") }
        assertFailsWith<IllegalArgumentException> { ChatMessage.ToolResult(" ", "{}") }
    }

    @Test
    fun `an assistant message can carry only tool calls, with unique ids`() {
        assertEquals(listOf(call), ChatMessage.Assistant(toolCalls = listOf(call)).toolCalls)
        assertFailsWith<IllegalArgumentException> { ChatMessage.Assistant(toolCalls = listOf(call, call)) }
    }

    @Test
    fun `tool specs take the names every API accepts`() {
        ToolSpec("roll_action", "Rolls", schema)
        ToolSpec("get-sheet", "Reads", schema)
        assertFailsWith<IllegalArgumentException> { ToolSpec("roll action", "Rolls", schema) }
        assertFailsWith<IllegalArgumentException> { ToolSpec("x".repeat(65), "Rolls", schema) }
        assertFailsWith<IllegalArgumentException> { ToolSpec("roll", " ", schema) }
    }

    @Test
    fun `tool call arguments are parsed when they are a JSON object`() {
        assertEquals(JsonPrimitive("edge"), call.parsedArguments()?.get("stat"))
        assertEquals(0, ToolCall("c", "t", "").parsedArguments()?.size)
        assertNull(ToolCall("c", "t", "[1]").parsedArguments())
        assertNull(ToolCall("c", "t", "{\"stat\":").parsedArguments())
        assertFailsWith<IllegalArgumentException> { ToolCall(" ", "t", "{}") }
        assertFailsWith<IllegalArgumentException> { ToolCall("c", "", "{}") }
    }

    @Test
    fun `a request checks its own consistency`() {
        val user = ChatMessage.User("I search the cabin")
        val tool = ToolSpec("roll_action", "Rolls", schema)
        ChatRequest("m", listOf(user), listOf(tool), ToolChoice.REQUIRED, temperature = 1.3, maxTokens = 400)
        ChatRequest("m", listOf(user), toolChoice = ToolChoice.NONE)
        assertFailsWith<IllegalArgumentException> { ChatRequest(" ", listOf(user)) }
        assertFailsWith<IllegalArgumentException> { ChatRequest("m", emptyList()) }
        assertFailsWith<IllegalArgumentException> { ChatRequest("m", listOf(user), listOf(tool, tool)) }
        assertFailsWith<IllegalArgumentException> { ChatRequest("m", listOf(user), toolChoice = ToolChoice.REQUIRED) }
        assertFailsWith<IllegalArgumentException> { ChatRequest("m", listOf(user), temperature = 2.5) }
        assertFailsWith<IllegalArgumentException> { ChatRequest("m", listOf(user), maxTokens = 0) }
    }

    @Test
    fun `a tool result must answer a call made earlier in the conversation`() {
        val asked = listOf(ChatMessage.User("Go"), ChatMessage.Assistant(toolCalls = listOf(call)))
        ChatRequest("m", asked + ChatMessage.ToolResult("call_1", "{}"))
        assertFailsWith<IllegalArgumentException> { ChatRequest("m", asked + ChatMessage.ToolResult("call_9", "{}")) }
    }
}
