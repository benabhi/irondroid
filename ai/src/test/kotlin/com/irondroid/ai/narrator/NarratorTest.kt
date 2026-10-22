package com.irondroid.ai.narrator

import com.irondroid.ai.AiErrorKind
import com.irondroid.ai.AiException
import com.irondroid.ai.ChatMessage
import com.irondroid.ai.ScriptedAiProvider
import com.irondroid.ai.ScriptedTurn
import com.irondroid.ai.TokenUsage
import com.irondroid.ai.ToolCall
import com.irondroid.ai.ToolChoice
import com.irondroid.ai.tools.ActionTools
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.SeededDice
import com.irondroid.engine.game.GameAccess
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NarratorTest {
    private class OpenGame : GameAccess {
        override var sheet: Character = Character.create(edge = 2, heart = 1, iron = 3, shadow = 2, wits = 1)
        override val characterName = "Kira"
        override val history: ActionHistory<Character>? = null
        override fun update(change: (Character) -> Character) {
            sheet = change(sheet)
        }

        override fun save() {}
    }

    private val game = OpenGame()
    private val actions = GameActions(game, MoveLoader.fromResources(), OracleTableLoader.fromResources(), SeededDice.fromSeed(5), "es")
    private val tools = ActionTools.forNarrator(actions.dispatcher())
    private var now = 0L

    private fun narrator(vararg turns: ScriptedTurn, maxRounds: Int = Narrator.MAX_ROUNDS, confirmer: MoveConfirmer? = null) =
        ScriptedAiProvider(turns.toList()).let {
            it to Narrator(it, "scripted", tools, { "PROMPT" }, maxRounds, clock = { now += 100; now }, confirmer = confirmer)
        }

    /** Answers every proposal with [answer] and keeps what it was asked. */
    private class Confirmer(val answer: (MoveProposal) -> MoveDecision) : MoveConfirmer {
        val asked = mutableListOf<MoveProposal>()
        override fun needsConfirmation(move: String) = move != "pay_the_price"
        override suspend fun confirm(proposal: MoveProposal) = answer(proposal).also { asked += proposal }
    }

    private val faceDanger = ToolCall("c1", "roll_move", """{"move":"face_danger","source":"edge"}""")

    @Test
    fun `a move waits for the player and rolls as proposed when accepted`() = runTest {
        val confirmer = Confirmer { MoveDecision.Roll(it.move, it.source) }
        val (ai, narrator) = narrator(ScriptedTurn.toolCalls(faceDanger), ScriptedTurn.text("Saltas."), confirmer = confirmer)
        val outcome = narrator.play("Salto", emptyList())
        assertEquals(listOf(MoveProposal("face_danger", "edge")), confirmer.asked)
        assertTrue(outcome.steps.single().succeeded)
        val result = assertIs<ChatMessage.ToolResult>(ai.requests[1].messages.last()).content
        assertFalse("player_changed" in result)
    }

    @Test
    fun `the player can change the stat or the move and the model is told`() = runTest {
        val confirmer = Confirmer { MoveDecision.Roll("secure_an_advantage", "wits") }
        val (ai, narrator) = narrator(ScriptedTurn.toolCalls(faceDanger), ScriptedTurn.text("Observas."), confirmer = confirmer)
        val step = narrator.play("Salto", emptyList()).steps.single()
        assertEquals("roll_move", step.action)
        assertTrue("secure_an_advantage" in step.arguments && "wits" in step.arguments, step.arguments)
        assertTrue(step.summary.startsWith("Asegurar una ventaja"), step.summary)
        val result = assertIs<ChatMessage.ToolResult>(ai.requests[1].messages.last()).content
        assertTrue("player_changed" in result, result)
    }

    @Test
    fun `skipping the roll tells the model and rolls nothing`() = runTest {
        val momentum = game.sheet.momentum.value
        val confirmer = Confirmer { MoveDecision.Skip }
        val (ai, narrator) = narrator(ScriptedTurn.toolCalls(faceDanger), ScriptedTurn.text("Esperas."), confirmer = confirmer)
        val outcome = narrator.play("Salto", emptyList())
        assertFalse(outcome.steps.single().succeeded)
        assertEquals(momentum, game.sheet.momentum.value)
        val result = assertIs<ChatMessage.ToolResult>(ai.requests[1].messages.last()).content
        assertTrue("declined" in result, result)
    }

    @Test
    fun `moves without confirmation and other tools are not asked about`() = runTest {
        val confirmer = Confirmer { error("not asked") }
        val (_, narrator) = narrator(
            ScriptedTurn.toolCalls(ToolCall("c1", "get_scene", "{}"), ToolCall("c2", "roll_move", """{"move":"pay_the_price"}""")),
            ScriptedTurn.text("Pagas."),
            confirmer = confirmer,
        )
        narrator.play("Sigo", emptyList())
        assertEquals(emptyList(), confirmer.asked)
    }

    @Test
    fun `a turn without stakes is narrated in one request`() = runTest {
        val (ai, narrator) = narrator(ScriptedTurn.text("Llueve sobre la aldea. ¿Qué haces?", usage = TokenUsage(10, 5)))
        val events = mutableListOf<TurnEvent>()
        val outcome = narrator.play("Miro alrededor", emptyList()) { events += it }

        assertEquals("Llueve sobre la aldea. ¿Qué haces?", outcome.narration)
        assertEquals(outcome.narration, events.filterIsInstance<TurnEvent.Text>().joinToString("") { it.text })
        assertEquals(emptyList(), outcome.steps)
        assertEquals(TokenUsage(10, 5), outcome.usage)
        assertNull(outcome.error)
        assertTrue(outcome.firstTextMs!! < outcome.totalMs)
        val request = ai.requests.single()
        assertEquals(listOf(ChatMessage.System("PROMPT"), ChatMessage.User("Miro alrededor")), request.messages)
        assertEquals(ActionTools.NARRATOR, request.tools.map { it.name }.toSet())
    }

    @Test
    fun `the engine rolls the move the model asks for and the model narrates the result`() = runTest {
        val roll = ToolCall("c1", "roll_move", """{"move":"face_danger","source":"edge"}""")
        val (ai, narrator) = narrator(
            ScriptedTurn.toolCalls(roll, usage = TokenUsage(100, 10, 50)),
            ScriptedTurn.text("Saltas el arroyo helado. ¿Qué haces?", usage = TokenUsage(120, 20, 100)),
        )
        val outcome = narrator.play("Salto el arroyo", emptyList())

        val step = outcome.steps.single()
        assertEquals("roll_move", step.action)
        assertTrue(step.succeeded, step.summary)
        assertEquals("face_danger", step.data!!["move"]!!.jsonPrimitive.content)
        assertEquals("Saltas el arroyo helado. ¿Qué haces?", outcome.narration)
        assertEquals(TokenUsage(220, 30, 150), outcome.usage)
        val second = ai.requests[1].messages
        assertIs<ChatMessage.Assistant>(second[2])
        assertEquals("c1", assertIs<ChatMessage.ToolResult>(second[3]).callId)
    }

    @Test
    fun `the context of the turn goes right after the prompt`() = runTest {
        val ai = ScriptedAiProvider(listOf(ScriptedTurn.text("Sigue.")))
        val context = TurnContext("Kira", mapOf("edge" to 2), health = 5, spirit = 4, supply = 3, momentum = 2)
        Narrator(ai, "scripted", tools, { "PROMPT" }, context = { context }).play("Miro", listOf(PastTurn("Antes", "Narró")))
        val messages = ai.requests.single().messages
        assertEquals(ChatMessage.System("PROMPT"), messages[0])
        assertEquals(ChatMessage.System(context.render()), messages[1])
        assertEquals(ChatMessage.User("Antes"), messages[2])
    }

    @Test
    fun `failed and player-only calls go back to the model as errors`() = runTest {
        val (_, narrator) = narrator(
            ScriptedTurn.toolCalls(ToolCall("c1", "adjust_meter", """{"meter":"health","amount":-1}""")),
            ScriptedTurn.text("Nada cambia."),
        )
        val step = narrator.play("Me corto", emptyList()).steps.single()
        assertEquals(false, step.succeeded)
    }

    @Test
    fun `past turns come before the new input, only the most recent ones`() = runTest {
        val past = (1..10).map { PastTurn("dijo $it", "narró $it") } + PastTurn("sin respuesta", null)
        val (ai, narrator) = narrator(ScriptedTurn.text("Sigue."))
        narrator.play("Ahora", past)

        val messages = ai.requests.single().messages
        assertEquals(ChatMessage.User("dijo 4"), messages[1])
        assertEquals(ChatMessage.Assistant("narró 10"), messages[messages.size - 3])
        assertEquals(ChatMessage.User("sin respuesta"), messages[messages.size - 2])
        assertEquals(ChatMessage.User("Ahora"), messages.last())
    }

    @Test
    fun `text of later rounds is a new paragraph`() = runTest {
        val (_, narrator) = narrator(
            ScriptedTurn.toolCalls(ToolCall("c1", "get_character", "{}"), text = "Te detienes."),
            ScriptedTurn.text("Sigues."),
        )
        assertEquals("Te detienes.\n\nSigues.", narrator.play("Espero", emptyList()).narration)
    }

    @Test
    fun `after the last round the model narrates without tools`() = runTest {
        val read = { id: String -> ScriptedTurn.toolCalls(ToolCall(id, "get_scene", "{}")) }
        val (ai, narrator) = narrator(read("a"), read("b"), ScriptedTurn.text("Al fin, el paso."), maxRounds = 2)
        val outcome = narrator.play("Busco", emptyList())
        assertEquals(3, ai.requests.size)
        assertEquals(listOf(ToolChoice.AUTO, ToolChoice.AUTO, ToolChoice.NONE), ai.requests.map { it.toolChoice })
        assertEquals(2, outcome.steps.size)
        assertEquals("Al fin, el paso.", outcome.narration)
    }

    @Test
    fun `tool calls in the last request are not run`() = runTest {
        val read = { id: String -> ScriptedTurn.toolCalls(ToolCall(id, "get_scene", "{}")) }
        val (_, narrator) = narrator(read("a"), read("b"), maxRounds = 1)
        assertEquals(1, narrator.play("Busco", emptyList()).steps.size)
    }

    @Test
    fun `an AI failure keeps what was already done`() = runTest {
        val (_, narrator) = narrator(
            ScriptedTurn.toolCalls(ToolCall("c1", "get_scene", "{}")),
            ScriptedTurn.failure(AiException(AiErrorKind.NETWORK, "offline"), partialText = "Avanz"),
        )
        val outcome = narrator.play("Avanzo", emptyList())
        assertEquals(AiErrorKind.NETWORK, outcome.error?.kind)
        assertEquals(1, outcome.steps.size)
        assertEquals("Avanz", outcome.narration)
    }

    @Test
    fun `a price the player wrote is told as a consequence, not as an action`() = runTest {
        val (ai, narrator) = narrator(ScriptedTurn.text("Te roban."))
        narrator.afterChoice("Me roban el cuchillo", "{}", emptyList(), ownConsequence = true)
        val last = assertIs<ChatMessage.User>(ai.requests.single().messages.last()).text
        assertTrue("wrote this consequence themselves" in last && "Me roban el cuchillo" in last, last)
    }

    @Test
    fun `a corrected turn is played again with the old narration and the correction`() = runTest {
        val (ai, narrator) = narrator(ScriptedTurn.text("Rodeas el río."))
        val outcome = narrator.correct("Cruzo el río", "Nadas hasta la otra orilla.", "Mi personaje no sabe nadar", emptyList())
        assertEquals("Rodeas el río.", outcome.narration)
        val last = assertIs<ChatMessage.User>(ai.requests.single().messages.last()).text
        assertTrue(last.startsWith("Cruzo el río"), last)
        assertTrue("Nadas hasta la otra orilla." in last && "Mi personaje no sabe nadar" in last, last)
    }

    @Test
    fun `after a choice the model gets what the player picked and the result`() = runTest {
        val (ai, narrator) = narrator(ScriptedTurn.text("Eliges seguir."))
        narrator.afterChoice("Seguir adelante", """{"summary":"ok"}""", listOf(PastTurn("Salto", "Caes.")))
        val last = assertIs<ChatMessage.User>(ai.requests.single().messages.last()).text
        assertTrue("Seguir adelante" in last && "\"summary\":\"ok\"" in last, last)
    }
}
