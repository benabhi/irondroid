package com.irondroid.ai.world

import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.AiException
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ReasoningEffort
import com.irondroid.ai.ScriptedAiProvider
import com.irondroid.ai.ScriptedTurn
import com.irondroid.ai.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruthSuggesterTest {
    private val categories = listOf(
        TruthChoices("iron", "Iron", mapOf("iron_1" to "Black iron.", "iron_2" to "Bleak weather.", "iron_3" to "Pillars.")),
        TruthChoices("horrors", "Horrors", mapOf("horrors_1" to "Stories.", "horrors_2" to "Monsters lurk.", "horrors_3" to "The dead walk.")),
        TruthChoices("beasts", "Beasts", mapOf("beasts_1" to "Legend.", "beasts_2" to "Monsters.", "beasts_3" to "Everywhere.")),
    )

    private fun suggester(vararg turns: ScriptedTurn) = ScriptedAiProvider(turns.toList()).let { it to TruthSuggester(it, "scripted", categories) }

    @Test
    fun `every known pick comes back with its reason`() = runTest {
        val arguments = """{"iron":{"option":"iron_3","reason":" Los pilares dan misterio. "},
            "horrors":{"option":"horrors_9","reason":"?"},"beasts":{"option":"beasts_2","reason":""}}"""
        val (ai, suggester) = suggester(ScriptedTurn.toolCalls(ToolCall("c1", "suggest_truths", arguments)))

        val picks = suggester.suggest("La cruzada", "Kira", "Las Tierras de Hierro", "es")

        assertEquals(mapOf("iron" to TruthSuggestion("iron_3", "Los pilares dan misterio."), "beasts" to TruthSuggestion("beasts_2", null)), picks)
        val request = ai.requests.single()
        val prompt = (request.messages.first() as ChatMessage.System).text
        assertTrue("## Iron (iron)\n- iron_1: Black iron." in prompt, prompt)
        assertTrue("language with code \"es\"" in prompt, prompt)
        assertEquals(ChatMessage.User("Campaign: La cruzada\nCharacter: Kira\nWorld: Las Tierras de Hierro"), request.messages[1])
        assertEquals(ReasoningEffort.LOW, request.reasoningEffort)
        val schema = request.tools.single().parameters
        assertEquals(listOf("iron", "horrors", "beasts"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val ironOptions = schema["properties"]!!.jsonObject["iron"]!!.jsonObject["properties"]!!.jsonObject["option"] as JsonObject
        assertEquals(listOf("iron_1", "iron_2", "iron_3"), ironOptions["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `text, bad arguments or a failure suggest nothing`() = runTest {
        val (_, text) = suggester(ScriptedTurn.text("Iron 3, I think."))
        assertEquals(emptyMap(), text.suggest("T", "K", "W", "en"))

        val (_, broken) = suggester(ScriptedTurn.toolCalls(ToolCall("c1", "suggest_truths", "not json")))
        assertEquals(emptyMap(), broken.suggest("T", "K", "W", "en"))

        val (_, wrongShape) = suggester(ScriptedTurn.toolCalls(ToolCall("c1", "suggest_truths", """{"iron":"iron_1","horrors":{"option":3}}""")))
        assertEquals(emptyMap(), wrongShape.suggest("T", "K", "W", "en"))

        val (_, failing) = suggester(ScriptedTurn.failure(AiException(AiErrorKind.NETWORK, "offline")))
        val failures = mutableListOf<AiException>()
        assertEquals(emptyMap(), failing.suggest("T", "K", "W", "en") { failures += it })
        assertEquals(listOf(AiErrorKind.NETWORK), failures.map { it.kind })
    }
}
