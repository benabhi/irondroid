package com.irondroid.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatEventTest {
    @Test
    fun `a stream is collected into one reply, passing text along as it arrives`() = runTest {
        val call = ToolCall("call_1", "ask_oracle", """{"odds":"likely"}""")
        val pieces = mutableListOf<String>()
        val reply = flowOf(
            ChatEvent.TextDelta("The door "),
            ChatEvent.TextDelta("creaks."),
            ChatEvent.ToolCallRequested(call),
            ChatEvent.Finished(FinishReason.TOOL_CALLS, TokenUsage(120, 30, 100)),
        ).collectReply { pieces += it }

        assertEquals(listOf("The door ", "creaks."), pieces)
        assertEquals("The door creaks.", reply.text)
        assertEquals(listOf(call), reply.toolCalls)
        assertEquals(FinishReason.TOOL_CALLS, reply.finishReason)
        assertEquals(150, reply.usage?.totalTokens)
        assertEquals(ChatMessage.Assistant("The door creaks.", listOf(call)), reply.toMessage())
    }

    @Test
    fun `a stream that never finishes, or goes on after finishing, is malformed`() = runTest {
        val unfinished = assertFailsWith<AiException> { flowOf(ChatEvent.TextDelta("Hi")).collectReply() }
        assertEquals(AiErrorKind.MALFORMED_RESPONSE, unfinished.kind)

        val trailing = assertFailsWith<AiException> {
            flowOf(ChatEvent.Finished(FinishReason.STOP), ChatEvent.TextDelta("more")).collectReply()
        }
        assertEquals(AiErrorKind.MALFORMED_RESPONSE, trailing.kind)
    }

    @Test
    fun `a reply becomes the assistant message only when it says something`() {
        assertNull(ChatReply(" ", emptyList(), FinishReason.STOP, null).toMessage())
        val onlyCalls = ChatReply("", listOf(ToolCall("c", "t", "{}")), FinishReason.TOOL_CALLS, null).toMessage()
        assertNull(onlyCalls?.text)
    }

    @Test
    fun `token usage adds up and refuses impossible counts`() {
        val total = TokenUsage(100, 20, 80) + TokenUsage(50, 10)
        assertEquals(TokenUsage(150, 30, 80), total)
        assertEquals(180, total.totalTokens)
        assertFailsWith<IllegalArgumentException> { TokenUsage(-1, 0) }
        assertFailsWith<IllegalArgumentException> { TokenUsage(10, 0, 11) }
    }

    @Test
    fun `error kinds say whether retrying makes sense`() {
        val limited = AiException(AiErrorKind.RATE_LIMITED, "Slow down", retryAfterSeconds = 20)
        assertEquals(true, limited.retryable)
        assertEquals(20, limited.retryAfterSeconds)
        assertEquals(false, AiException(AiErrorKind.AUTHENTICATION, "Bad key").retryable)
        val retryable = AiErrorKind.entries.filter { it.retryable }.toSet()
        assertEquals(setOf(AiErrorKind.RATE_LIMITED, AiErrorKind.NETWORK, AiErrorKind.SERVER, AiErrorKind.MALFORMED_RESPONSE), retryable)
    }

    @Test
    fun `reasoning is passed along apart from the text`() = runTest {
        val thoughts = mutableListOf<String>()
        val reply = flowOf(
            ChatEvent.ReasoningDelta("Hmm, "),
            ChatEvent.ReasoningDelta("a door."),
            ChatEvent.TextDelta("It opens."),
            ChatEvent.Finished(FinishReason.STOP),
        ).collectReply(onReasoning = { thoughts += it })

        assertEquals(listOf("Hmm, ", "a door."), thoughts)
        assertEquals("Hmm, a door.", reply.reasoning)
        assertEquals("It opens.", reply.text)
    }
}
