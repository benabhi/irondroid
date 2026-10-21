package com.irondroid.data.session

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.irondroid.data.CampaignStore
import com.irondroid.data.IrondroidDatabase
import com.irondroid.engine.action.ActionArea
import com.irondroid.engine.action.ActionCatalog
import com.irondroid.engine.action.ActionDefinition
import com.irondroid.engine.action.ActionDispatcher
import com.irondroid.engine.action.ActionEffect
import com.irondroid.engine.action.ActionHandler
import com.irondroid.engine.action.ActionPermission
import com.irondroid.engine.action.ActionReturn
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.CatalogVersion
import com.irondroid.engine.character.Character
import com.irondroid.engine.meters.MeterType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** An action played on the phone is saved to a database file and is still there after reopening it. */
@RunWith(AndroidJUnit4::class)
class DeviceAutosaveTest {
    @Test
    fun actionIsSavedAndSurvivesReopening() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "device-autosave-test.db"
        context.deleteDatabase(name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val first = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        val store = CampaignStore(first)
        val campaignId = store.createCampaignInNewWorld("Ironlands", "Device", "Kira", Character.create(3, 2, 2, 1, 1))
        val session = GameSession.open(store, campaignId)!!
        val loseSupply = ActionDefinition(
            name = "lose_supply",
            area = ActionArea.STATE,
            description = "Loses one supply",
            whenToUse = "A test needs it",
            permission = ActionPermission.PLAY,
            returns = ActionReturn("Nothing"),
            effects = setOf(ActionEffect.CHANGES_CHARACTER),
        )
        val dispatcher = ActionDispatcher(
            ActionCatalog.of(CatalogVersion(1, 0), listOf(loseSupply)),
            mapOf(
                "lose_supply" to ActionHandler { _, _ ->
                    session.update { it.copy(meters = it.meters.lose(MeterType.SUPPLY, 2).first) }
                    JsonObject(emptyMap())
                },
            ),
        )
        val autosave = Autosave(store, session, scope)
        dispatcher.addListener(autosave)

        dispatcher.dispatch("lose_supply", "", Caller.PLAYER)
        autosave.flush()
        scope.cancel()
        first.close()

        val reopened = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        try {
            assertEquals(3, CampaignStore(reopened).loadCharacter(campaignId)!!.sheet.meters.supply.value)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
