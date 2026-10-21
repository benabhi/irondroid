package com.irondroid.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.irondroid.data.CampaignStore
import com.irondroid.data.IrondroidDatabase
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.CallOutcome
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.action.UndoRole
import com.irondroid.engine.character.Character
import com.irondroid.engine.dice.Dice
import com.irondroid.engine.dice.Die
import com.irondroid.engine.dice.DieRoll
import com.irondroid.engine.game.GameAccess
import com.irondroid.engine.game.GameActions
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.move.MoveLoader
import com.irondroid.engine.oracle.OracleTableLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A whole game played through the actions interface with the real database (IRON-44): the AI acts,
 * the app closes with a choice open, opens again, and the player undoes.
 */
@RunWith(RobolectricTestRunner::class)
class GameFlowTest {
    private class QueuedDice : Dice {
        val queue = ArrayDeque<Int>()

        override fun roll(die: Die) = DieRoll(die, checkNotNull(queue.removeFirstOrNull()) { "No value queued for $die" })
    }

    /** The open game over a session and its turn history, as GameController does in the app. */
    private class SessionGame(val session: GameSession, val turns: TurnRecorder) : GameAccess {
        override val sheet: Character get() = session.sheet.value
        override val characterName: String get() = session.characterName
        override val history: ActionHistory<Character> get() = turns.history

        override fun update(change: (Character) -> Character) = session.update(change)

        override fun save() = Unit
    }

    /** What runs while the app is open: the game, its actions, autosave and history. */
    private inner class RunningApp(val game: SessionGame, val dispatcher: ActionDispatcher, val autosave: Autosave) {
        fun call(name: String, json: String = "", caller: Caller = Caller.AI) = dispatcher.dispatch(name, json, caller)

        suspend fun close() {
            game.turns.flush()
            autosave.flush()
            game.turns.close()
            autosave.close()
            game.turns.join()
            autosave.join()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dice = QueuedDice()
    private lateinit var database: IrondroidDatabase
    private lateinit var store: CampaignStore
    private var campaignId = 0L

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(context, IrondroidDatabase::class.java).build()
        store = CampaignStore(database)
        campaignId = store.createCampaignInNewWorld("Ironlands", "Flow", "Kira", Character.create(0, 0, 0, 0, 0))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun start(scope: CoroutineScope): RunningApp {
        val session = GameSession.open(store, campaignId)!!
        val actions = GameActions(
            game = LateGame(),
            moves = MOVES,
            oracles = ORACLES,
            dice = dice,
            language = "es",
        )
        val turns = TurnRecorder.open(store, session, actions.choices, scope)
        val game = SessionGame(session, turns)
        lateGame = game
        val dispatcher = actions.dispatcher()
        val autosave = Autosave(store, session, scope)
        dispatcher.addListener(turns.history)
        dispatcher.addListener(autosave)
        return RunningApp(game, dispatcher, autosave)
    }

    /** The actions need the game before the turn history exists, as with the app's lazy wiring. */
    private var lateGame: SessionGame? = null

    private inner class LateGame : GameAccess {
        private val game get() = lateGame ?: throw GameAccess.noGame()
        override val sheet get() = game.sheet
        override val characterName get() = game.characterName
        override val history get() = lateGame?.history

        override fun update(change: (Character) -> Character) = game.update(change)

        override fun save() = game.save()
    }

    private suspend fun saved() = store.loadCharacter(campaignId)!!.sheet

    @Test
    fun `a game survives closing with a choice open and can be undone after reopening`() = runTest {
        val first = start(backgroundScope)
        assertIs<DispatchResult.Success>(first.call("create_character", """{"edge":3,"heart":2,"iron":2,"shadow":1,"wits":1}"""))
        dice.queue += listOf(6, 1, 1)
        val vow = assertIs<DispatchResult.Success>(first.call("roll_move", """{"move":"swear_an_iron_vow","new_track_name":"Vengar a Kai","new_track_rank":"dangerous"}""")).result
        assertTrue(vow.summary.startsWith("Jurar un voto de hierro"), vow.summary)
        // Endure Harm 2: health drops before the roll; a strong hit then offers a choice.
        dice.queue += listOf(6, 1, 1)
        val harm = assertIs<DispatchResult.Success>(first.call("roll_move", """{"move":"endure_harm","amount":2}""")).result
        val choice = assertNotNull(harm.pendingChoice)
        assertIs<DispatchResult.Success>(first.call("suggest_option", """{"choice":"${choice.id}","options":["${choice.options.first().id}"],"why":"Keep going"}"""))
        assertIs<DispatchResult.Failure>(first.call("roll_move", """{"move":"face_danger","source":"edge"}"""))
        first.close()
        assertEquals(3, saved().meters[MeterType.HEALTH].value)

        // The app opens again: the open choice is gone, so the game goes back to before Endure Harm.
        val second = start(backgroundScope)
        assertNull(second.dispatcher.choices.current)
        assertEquals(5, second.game.sheet.meters[MeterType.HEALTH].value)
        assertEquals(5, saved().meters[MeterType.HEALTH].value)
        assertEquals(setOf("vow_1"), saved().tracks.keys)
        assertEquals(4, saved().momentum.value)

        // The player takes back the vow.
        val undone = assertIs<DispatchResult.Success>(second.call("undo", caller = Caller.PLAYER)).result
        assertTrue(undone.summary.startsWith("Undid roll_move"), undone.summary)
        second.close()
        assertEquals(emptyMap(), saved().tracks)
        assertEquals(2, saved().momentum.value)
        assertEquals(listOf(3, 2, 2, 1, 1), saved().stats.values.toList())

        val calls = store.actionCalls(campaignId)
        assertEquals(
            listOf("create_character", "roll_move", "roll_move", "suggest_option", "roll_move", "undo"),
            calls.map { it.actionName },
        )
        assertEquals(listOf(Caller.AI, Caller.AI, Caller.AI, Caller.AI, Caller.AI, Caller.PLAYER), calls.map { it.caller })
        assertEquals(
            listOf(CallOutcome.SUCCESS, CallOutcome.SUCCESS, CallOutcome.SUCCESS, CallOutcome.SUCCESS, CallOutcome.FAILURE, CallOutcome.SUCCESS),
            calls.map { it.outcome },
        )
        assertEquals(
            listOf(UndoRole.START, UndoRole.START, UndoRole.START, UndoRole.PART, UndoRole.NONE, UndoRole.NONE),
            calls.map { it.undoRole },
        )
        assertEquals(listOf(false, true, true, true, false, false), calls.map { it.undone })
        assertTrue(calls[2].choiceOpenAfter)
    }

    private companion object {
        val MOVES = MoveLoader.fromResources()
        val ORACLES = OracleTableLoader.fromResources()
    }
}
