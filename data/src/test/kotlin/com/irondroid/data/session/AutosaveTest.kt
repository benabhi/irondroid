package com.irondroid.data.session

import android.content.Context
import androidx.room.Room
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
import com.irondroid.engine.action.ActionRejectedException
import com.irondroid.engine.action.ActionReturn
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.CatalogVersion
import com.irondroid.engine.action.DispatchResult
import com.irondroid.engine.character.Character
import com.irondroid.engine.meters.MeterType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AutosaveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: IrondroidDatabase
    private lateinit var store: CampaignStore
    private var now = 0L
    private var campaignId = 0L
    private lateinit var session: GameSession

    private fun definition(name: String, permission: ActionPermission, effects: Set<ActionEffect>) = ActionDefinition(
        name = name,
        area = ActionArea.STATE,
        description = "Test action $name",
        whenToUse = "A test needs it",
        permission = permission,
        returns = ActionReturn("Nothing"),
        effects = effects,
    )

    /** A tiny game over the session: one action loses health, one only reads, one is always refused. */
    private fun dispatcher() = ActionDispatcher(
        ActionCatalog.of(
            CatalogVersion(1, 0),
            listOf(
                definition("lose_health", ActionPermission.PLAY, setOf(ActionEffect.CHANGES_CHARACTER)),
                definition("read_sheet", ActionPermission.READ, emptySet()),
                definition("refused", ActionPermission.PLAY, setOf(ActionEffect.CHANGES_CHARACTER)),
            ),
        ),
        mapOf(
            "lose_health" to ActionHandler { _, _ ->
                session.update { it.copy(meters = it.meters.lose(MeterType.HEALTH, 1).first) }
                JsonObject(emptyMap())
            },
            "read_sheet" to ActionHandler { _, _ -> JsonObject(emptyMap()) },
            "refused" to ActionHandler { _, _ ->
                session.update { it.copy(experience = 9) }
                throw ActionRejectedException.rule("Never allowed")
            },
        ),
    )

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(context, IrondroidDatabase::class.java).build()
        store = CampaignStore(database) { ++now }
        campaignId = store.createCampaignInNewWorld("Ironlands", "Test", "Kira", Character.create(3, 2, 2, 1, 1))
        session = GameSession.open(store, campaignId)!!
    }

    @After
    fun tearDown() = database.close()

    private suspend fun savedHealth() = store.loadCharacter(campaignId)!!.sheet.meters.health.value

    @Test
    fun `an action that changes the game is saved`() = runTest {
        val autosave = Autosave(store, session, backgroundScope)
        val dispatcher = dispatcher().apply { addListener(autosave) }

        assertIs<DispatchResult.Success>(dispatcher.dispatch("lose_health", "", Caller.PLAYER))
        autosave.flush()

        assertEquals(4, savedHealth())
        assertEquals(session.sheet.value, store.loadCharacter(campaignId)!!.sheet)
    }

    @Test
    fun `actions that only read, or fail, save nothing`() = runTest {
        val autosave = Autosave(store, session, backgroundScope)
        val dispatcher = dispatcher().apply { addListener(autosave) }
        val before = store.campaign(campaignId)!!.updatedAt

        dispatcher.dispatch("read_sheet", "", Caller.AI)
        assertIs<DispatchResult.Failure>(dispatcher.dispatch("refused", "", Caller.AI))
        autosave.flush()

        assertEquals(before, store.campaign(campaignId)!!.updatedAt)
        assertEquals(0, store.loadCharacter(campaignId)!!.sheet.experience)
    }

    @Test
    fun `actions in a row end in one save with the latest sheet`() = runTest {
        val autosave = Autosave(store, session, backgroundScope)
        val dispatcher = dispatcher().apply { addListener(autosave) }
        val clockBefore = now

        repeat(5) { dispatcher.dispatch("lose_health", "", Caller.AI) }
        autosave.flush()

        assertEquals(0, savedHealth())
        assertEquals(1L, now - clockBefore, "one save touches the campaign once")
    }

    @Test
    fun `a failed save is reported and later saves still work`() = runTest {
        val errors = mutableListOf<Throwable>()
        val missing = GameSession(campaignId = 999, characterName = "Nobody", sheet = session.sheet.value)
        val broken = Autosave(store, missing, backgroundScope) { errors += it }

        broken.requestSave()
        broken.flush()
        broken.requestSave()
        broken.flush()

        assertEquals(2, errors.size)
        assertIs<IllegalStateException>(errors.first())
    }

    @Test
    fun `closing still writes what was pending`() = runTest {
        val autosave = Autosave(store, session, backgroundScope)
        session.update { it.copy(experience = 2) }

        autosave.requestSave()
        autosave.close()
        autosave.join()
        autosave.flush()

        assertEquals(2, store.loadCharacter(campaignId)!!.sheet.experience)
    }

    @Test
    fun `a session opens with the saved sheet, or not at all`() = runTest {
        assertEquals("Kira", session.characterName)
        assertEquals(store.loadCharacter(campaignId)!!.sheet, session.sheet.value)
        assertNull(GameSession.open(store, 999))
    }

    @Test
    fun `flush with nothing pending returns`() = runTest {
        val autosave = Autosave(store, session, backgroundScope)

        autosave.flush()

        assertTrue(store.campaign(campaignId) != null)
    }
}
