package com.irondroid.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolCallAssemblerTest {
    @Test
    fun `calls streamed in pieces are rebuilt in order`() {
        val assembler = ToolCallAssembler()
        // Two calls interleaved, the way OpenAI-compatible APIs stream parallel tool calls.
        assembler.add(1, id = "call_b", name = "ask_oracle", arguments = "")
        assembler.add(0, id = "call_a", name = "roll_action", arguments = "{\"st")
        assembler.add(0, arguments = "at\":\"edge\"}")
        assembler.add(1, arguments = "{\"odds\":\"likely\"}")

        assertEquals(
            listOf(
                ToolCall("call_a", "roll_action", """{"stat":"edge"}"""),
                ToolCall("call_b", "ask_oracle", """{"odds":"likely"}"""),
            ),
            assembler.complete(),
        )
        assertTrue(assembler.isEmpty)
    }

    @Test
    fun `a repeated name is kept once and missing arguments become an empty object`() {
        val assembler = ToolCallAssembler()
        assembler.add(0, id = "c", name = "get_sheet")
        assembler.add(0, name = "get_sheet", id = "")
        assertEquals(listOf(ToolCall("c", "get_sheet", "{}")), assembler.complete())
    }

    @Test
    fun `a call without id or name is a malformed response`() {
        val noId = ToolCallAssembler().apply { add(0, name = "get_sheet") }
        assertEquals(AiErrorKind.MALFORMED_RESPONSE, assertFailsWith<AiException> { noId.complete() }.kind)

        val noName = ToolCallAssembler().apply { add(0, id = "c", arguments = "{}") }
        assertEquals(AiErrorKind.MALFORMED_RESPONSE, assertFailsWith<AiException> { noName.complete() }.kind)

        assertFailsWith<IllegalArgumentException> { ToolCallAssembler().add(-1, id = "c") }
    }
}
