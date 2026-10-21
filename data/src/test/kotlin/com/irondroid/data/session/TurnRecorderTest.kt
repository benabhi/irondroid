package com.irondroid.data.session

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.irondroid.data.CampaignStore
import com.irondroid.data.IrondroidDatabase
import com.irondroid.engine.action.ActionArea
import com.irondroid.engine.action.ActionCatalog
import com.irondroid.engine.action.ActionDefinition
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.ActionEffect
import com.irondroid.engine.action.ActionHandler
import com.irondroid.engine.action.ActionPermission
import com.irondroid.engine.action.ActionResult
import com.irondroid.engine.action.ActionReturn
import com.irondroid.engine.action.CallOutcome
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.CatalogVersion
import com.irondroid.engine.action.ChoiceOption
import com.irondroid.engine.action.JsonSchema
import com.irondroid.engine.action.PendingChoice
import com.irondroid.engine.action.UndoRole
import com.irondroid.engine.character.Character
import com.irondroid.engine.meters.MeterType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TurnRecorderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: IrondroidDatabase
    private lateinit var store: CampaignStore
    private var campaignId = 0L
    private lateinit var session: GameSession

    @get:Rule
    val migrations = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        IrondroidDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun definition(name: String, effects: Set<ActionEffect>, answersChoice: Boolean = false, parameters: JsonSchema.ObjectOf = JsonSchema.ObjectOf()) =
        ActionDefinition(name, ActionArea.STATE, "Test $name", "A test needs it", ActionPermission.PLAY, ActionReturn("A result"), parameters, effects, answersChoice)

    private fun loseHealth() = session.update { it.copy(meters = it.meters.lose(MeterType.HEALTH, 1).first) }

    /** A tiny game: losing health, a move that asks how to pay, and undo. */
    private fun dispatcher(recorder: () -> TurnRecorder): ActionDispatcher {
        lateinit var dispatcher: ActionDispatcher
        dispatcher = ActionDispatcher(
            ActionCatalog.of(
                CatalogVersion(1, 0),
                listOf(
                    definition("lose_health", setOf(ActionEffect.CHANGES_CHARACTER)),
                    definition("face_danger", setOf(ActionEffect.ROLLS_DICE, ActionEffect.CHANGES_CHARACTER)),
                    definition("choose_option", setOf(ActionEffect.CHANGES_CHARACTER), answersChoice = true, parameters = JsonSchema.obj { string("option", "Option") }),
                    definition("undo", setOf(ActionEffect.CHANGES_CHARACTER), answersChoice = true),
                ),
            ),
            mapOf(
                "lose_health" to ActionHandler { _, _ ->
                    loseHealth()
                    ActionResult("Lost 1 health")
                },
                "face_danger" to ActionHandler { _, _ ->
                    loseHealth()
                    ActionResult(
                        "Weak hit",
                        pendingChoice = PendingChoice("Pay", listOf(ChoiceOption("harm", "Lose 1 health"), ChoiceOption("none", "Nothing"))) {
                            if (it == listOf("harm")) loseHealth()
                            ActionResult("Paid")
                        },
                    )
                },
                "choose_option" to ActionHandler { args, call ->
                    dispatcher.choices.decide(dispatcher.choices.current!!.id, listOf(args.string("option")), call.caller)
                },
                "undo" to ActionHandler { _, _ -> ActionResult("Undid ${recorder().history.undo().size} calls") },
            ),
        )
        return dispatcher
    }

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(context, IrondroidDatabase::class.java).build()
        store = CampaignStore(database) { 0L }
        campaignId = store.createCampaignInNewWorld("Ironlands", "Test", "Kira", Character.create(3, 2, 2, 1, 1))
        session = GameSession.open(store, campaignId)!!
    }

    @After
    fun tearDown() = database.close()

    private suspend fun health() = store.loadCharacter(campaignId)!!.sheet.meters.health.value

    @Test
    fun `every call is saved with its turn, caller and the sheet to undo to`() = runTest {
        lateinit var recorder: TurnRecorder
        val dispatcher = dispatcher { recorder }
        recorder = TurnRecorder.open(store, session, dispatcher.choices, backgroundScope)
        dispatcher.addListener(recorder.history)
        val sheetBefore = session.sheet.value

        dispatcher.dispatch("lose_health", "", Caller.AI)
        dispatcher.dispatch("missing", "", Caller.PLAYER)
        recorder.flush()
        store.appendTurn(campaignId, "I run", "You run")
        dispatcher.dispatch("lose_health", "", Caller.PLAYER)
        recorder.flush()

        val rows = store.actionCalls(campaignId)
        assertEquals(listOf(1, 2, 3), rows.map { it.sequence })
        assertEquals(listOf(1, 1, 2), rows.map { it.turnNumber })
        assertEquals(listOf(Caller.AI, Caller.PLAYER, Caller.PLAYER), rows.map { it.caller })
        assertEquals(listOf(CallOutcome.SUCCESS, CallOutcome.FAILURE, CallOutcome.SUCCESS), rows.map { it.outcome })
        assertEquals(listOf(UndoRole.START, UndoRole.NONE, UndoRole.START), rows.map { it.undoRole })
        assertEquals(sheetBefore, Json.decodeFromString(Character.serializer(), rows[0].before!!))
        assertNull(rows[1].before)
        assertEquals("{}", rows[0].arguments)
        assertTrue(rows[1].details.contains("unknown_action"))
    }

    @Test
    fun `undo restores the sheet and is remembered after reopening`() = runTest {
        lateinit var recorder: TurnRecorder
        val dispatcher = dispatcher { recorder }
        recorder = TurnRecorder.open(store, session, dispatcher.choices, backgroundScope)
        dispatcher.addListener(recorder.history)

        dispatcher.dispatch("lose_health", "", Caller.AI)
        dispatcher.dispatch("lose_health", "", Caller.AI)
        dispatcher.dispatch("undo", "", Caller.PLAYER)
        recorder.flush()
        assertEquals(4, session.sheet.value.meters.health.value)
        assertEquals(listOf(false, true, false), store.actionCalls(campaignId).map { it.undone })

        recorder.close()
        recorder.join()
        val reopened = TurnRecorder.open(store, session, dispatcher.choices, backgroundScope)
        recorder = reopened
        assertEquals(3, reopened.history.calls.size)
        assertTrue(reopened.history.canUndo)
        reopened.history.undo()
        reopened.flush()
        assertEquals(5, session.sheet.value.meters.health.value)
        assertTrue(store.actionCalls(campaignId).first().undone)
    }

    @Test
    fun `a choice left open is undone when the game opens again`() = runTest {
        lateinit var recorder: TurnRecorder
        val dispatcher = dispatcher { recorder }
        recorder = TurnRecorder.open(store, session, dispatcher.choices, backgroundScope)
        dispatcher.addListener(recorder.history)
        dispatcher.dispatch("lose_health", "", Caller.AI)
        dispatcher.dispatch("face_danger", "", Caller.AI)
        recorder.flush()
        store.saveCharacter(campaignId, session.sheet.value)
        assertEquals(3, health())

        // The app closes with the choice open; the game opens again in a new session.
        recorder.close()
        recorder.join()
        dispatcher.choices.cancel()
        val reopenedSession = GameSession.open(store, campaignId)!!
        val reopened = TurnRecorder.open(store, reopenedSession, dispatcher.choices, backgroundScope)
        reopened.flush()

        assertEquals(4, reopenedSession.sheet.value.meters.health.value)
        assertEquals(4, health())
        assertEquals(listOf(false, true), store.actionCalls(campaignId).map { it.undone })
        assertNull(dispatcher.choices.current)
        assertTrue(reopened.history.canUndo)
    }

    @Test
    fun `a game without an open choice opens as it was`() = runTest {
        lateinit var recorder: TurnRecorder
        val dispatcher = dispatcher { recorder }
        recorder = TurnRecorder.open(store, session, dispatcher.choices, backgroundScope)
        dispatcher.addListener(recorder.history)
        dispatcher.dispatch("face_danger", "", Caller.AI)
        dispatcher.dispatch("choose_option", """{"option":"harm"}""", Caller.PLAYER)
        recorder.flush()
        store.saveCharacter(campaignId, session.sheet.value)
        recorder.close()
        recorder.join()

        val reopened = TurnRecorder.open(store, GameSession.open(store, campaignId)!!, dispatcher.choices, backgroundScope)
        assertEquals(3, health())
        assertEquals(listOf(UndoRole.START, UndoRole.PART), reopened.history.calls.map { it.role })
        assertFalse(reopened.history.calls.any { it.undone })
    }

    @Test
    fun `failed writes are reported`() = runTest {
        val errors = mutableListOf<Throwable>()
        lateinit var recorder: TurnRecorder
        val dispatcher = dispatcher { recorder }
        val ghost = GameSession(campaignId = 999, characterName = "Nobody", sheet = session.sheet.value)
        recorder = TurnRecorder.open(store, ghost, dispatcher.choices, backgroundScope) { errors += it }
        dispatcher.addListener(recorder.history)

        dispatcher.dispatch("lose_health", "", Caller.AI)
        recorder.flush()

        assertEquals(1, errors.size)
    }

    @Test
    fun `version 1 databases gain the call log`() {
        migrations.createDatabase(migrationPath(), 1).apply {
            execSQL("INSERT INTO worlds (id, name, createdAt) VALUES (1, 'Ironlands', 0)")
            execSQL("INSERT INTO campaigns (id, worldId, title, status, createdAt, updatedAt) VALUES (1, 1, 'Old', 'ACTIVE', 0, 0)")
            close()
        }
        migrations.runMigrationsAndValidate(migrationPath(), 2, true).apply {
            query("SELECT COUNT(*) FROM action_calls").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT title FROM campaigns").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Old", cursor.getString(0))
            }
            close()
        }
    }

    /** A full path: with only a name, Room's helper and the driver disagree on where the file is under Robolectric. */
    private fun migrationPath() = context.getDatabasePath(MIGRATION_DB).absolutePath

    private companion object {
        const val MIGRATION_DB = "migration-test"
    }
}
