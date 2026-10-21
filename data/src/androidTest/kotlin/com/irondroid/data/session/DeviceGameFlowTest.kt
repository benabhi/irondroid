package com.irondroid.data.session

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.irondroid.data.CampaignStore
import com.irondroid.data.IrondroidDatabase
import com.irondroid.engine.action.ActionHistory
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.DispatchResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The game flow of IRON-44 on the phone: actions played through the dispatcher are saved to a real
 * database file; a choice left open when the app closes is undone when the game opens again.
 */
@RunWith(AndroidJUnit4::class)
class DeviceGameFlowTest {
    private class QueuedDice(vararg values: Int) : Dice {
        private val queue = ArrayDeque(values.toList())

        override fun roll(die: Die) = DieRoll(die, checkNotNull(queue.removeFirstOrNull()) { "No value queued for $die" })
    }

    /** The open game: filled in once its turn history is open, as the app does. */
    private class OpenGame : GameAccess {
        var session: GameSession? = null
        var turns: TurnRecorder? = null

        override val sheet: Character get() = (session ?: throw GameAccess.noGame()).sheet.value
        override val characterName: String get() = (session ?: throw GameAccess.noGame()).characterName
        override val history: ActionHistory<Character>? get() = turns?.history

        override fun update(change: (Character) -> Character) = (session ?: throw GameAccess.noGame()).update(change)

        override fun save() = Unit
    }

    @Test
    fun choiceLeftOpenIsUndoneAfterReopening() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "device-game-flow-test.db"
        context.deleteDatabase(name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val moves = MoveLoader.fromResources()
        val oracles = OracleTableLoader.fromResources()

        // First run: create the character and make Endure Harm 2, which stops at a choice.
        val firstDatabase = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        val firstStore = CampaignStore(firstDatabase)
        val campaignId = firstStore.createCampaignInNewWorld("Ironlands", "Device", "Kira", Character.create(0, 0, 0, 0, 0))
        val game = OpenGame()
        val actions = GameActions(game, moves, oracles, QueuedDice(6, 1, 1), "es")
        game.session = GameSession.open(firstStore, campaignId)!!
        game.turns = TurnRecorder.open(firstStore, game.session!!, actions.choices, scope)
        val autosave = Autosave(firstStore, game.session!!, scope)
        val dispatcher = actions.dispatcher().apply {
            addListener(game.turns!!.history)
            addListener(autosave)
        }
        assertIs<DispatchResult.Success>(dispatcher.dispatch("create_character", """{"edge":3,"heart":2,"iron":2,"shadow":1,"wits":1}""", Caller.AI))
        val harm = assertIs<DispatchResult.Success>(dispatcher.dispatch("roll_move", """{"move":"endure_harm","amount":2}""", Caller.AI))
        assertNotNull(harm.result.pendingChoice)
        game.turns!!.flush()
        autosave.flush()
        game.turns!!.close()
        autosave.close()
        firstDatabase.close()

        // Second run, from the file: the game is back to before Endure Harm.
        val secondDatabase = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        val secondStore = CampaignStore(secondDatabase)
        val reopened = OpenGame()
        val again = GameActions(reopened, moves, oracles, QueuedDice(), "es")
        reopened.session = GameSession.open(secondStore, campaignId)!!
        reopened.turns = TurnRecorder.open(secondStore, reopened.session!!, again.choices, scope)
        reopened.turns!!.flush()

        assertNull(again.choices.current)
        val saved = secondStore.loadCharacter(campaignId)!!.sheet
        assertEquals(5, saved.meters[MeterType.HEALTH].value)
        assertEquals(listOf(3, 2, 2, 1, 1), saved.stats.values.toList())
        assertEquals(listOf(false, true), secondStore.actionCalls(campaignId).map { it.undone })

        reopened.turns!!.close()
        secondDatabase.close()
        scope.cancel()
        context.deleteDatabase(name)
    }
}
