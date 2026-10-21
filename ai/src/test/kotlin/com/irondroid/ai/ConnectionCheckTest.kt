package com.irondroid.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ConnectionCheckTest {
    private var now = 1_000L
    private val clock = { now.also { now += 250 } }

    @Test
    fun `a model that calls the test tool is ready to play`() = runTest {
        val provider = ScriptedAiProvider(listOf(ScriptedTurn.toolCalls(ToolCall("c", "connection_check", """{"ok":true}"""))))

        val result = ConnectionCheck.run(provider, "scripted", clock)

        assertEquals(ConnectionResult.Ready(250), result)
        val sent = provider.requests.single()
        assertEquals(listOf(ConnectionCheck.PING), sent.tools)
        assertEquals("scripted", sent.model)
    }

    @Test
    fun `a model that only writes text has no tools`() = runTest {
        val provider = ScriptedAiProvider(listOf(ScriptedTurn.text("ok")))
        assertIs<ConnectionResult.NoTools>(ConnectionCheck.run(provider, "scripted", clock))
    }

    @Test
    fun `a refused request says why`() = runTest {
        val error = AiException(AiErrorKind.AUTHENTICATION, "Bad key")
        val provider = ScriptedAiProvider(listOf(ScriptedTurn.failure(error)))

        val result = assertIs<ConnectionResult.Failed>(ConnectionCheck.run(provider, "scripted", clock))
        assertSame(error, result.error)
        assertEquals(250, result.millis)
    }

    @Test
    fun `the default clock measures real time`() = runTest {
        val result = ConnectionCheck.run(ScriptedAiProvider(listOf(ScriptedTurn.text("ok"))), "scripted")
        assertEquals(true, result.millis >= 0)
    }
}
