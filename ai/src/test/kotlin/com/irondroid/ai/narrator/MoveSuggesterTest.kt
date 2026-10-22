package com.irondroid.ai.narrator

import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.AiException
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ScriptedAiProvider
import com.irondroid.ai.ScriptedTurn
import com.irondroid.ai.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveSuggesterTest {
    private val moves = listOf(
        MoveOption("face_danger", "When you attempt something risky.\nRoll +edge..."),
        MoveOption("gather_information", "When you search an area."),
        MoveOption("heal", "When you treat an injury."),
    )

    private fun suggester(vararg turns: ScriptedTurn) = ScriptedAiProvider(turns.toList()).let { it to MoveSuggester(it, "scripted", moves) }

    @Test
    fun `the moves the model picks come back in its order, known ones only and at most three`() = runTest {
        val call = ToolCall("c1", "suggest_moves", """{"moves":["heal","unknown","face_danger","heal","gather_information"]}""")
        val (ai, suggester) = suggester(ScriptedTurn.toolCalls(call))

        assertEquals(listOf("heal", "face_danger", "gather_information"), suggester.suggest("Sangras.", "Me miro la herida"))
        val request = ai.requests.single()
        val prompt = (request.messages.first() as ChatMessage.System).text
        assertTrue("- face_danger: When you attempt something risky." in prompt, prompt)
        assertTrue("Roll +edge" !in prompt, prompt)
        assertEquals(ChatMessage.User("The player said: Me miro la herida\n\nThe narrator answered: Sangras."), request.messages[1])
        assertEquals(listOf("suggest_moves"), request.tools.map { it.name })
    }

    @Test
    fun `text, bad arguments or a failure suggest nothing`() = runTest {
        val (_, text) = suggester(ScriptedTurn.text("Face danger, probably."))
        assertEquals(emptyList(), text.suggest("Llueve.", null))

        val (_, broken) = suggester(ScriptedTurn.toolCalls(ToolCall("c1", "suggest_moves", "not json")))
        assertEquals(emptyList(), broken.suggest("Llueve.", null))

        val (_, failing) = suggester(ScriptedTurn.failure(AiException(AiErrorKind.NETWORK, "offline")))
        assertEquals(emptyList(), failing.suggest("Llueve.", null))
    }

    @Test
    fun `an empty scene asks nothing`() = runTest {
        val (ai, suggester) = suggester()
        assertEquals(emptyList(), suggester.suggest("", " "))
        assertEquals(0, ai.requests.size)
    }
}
