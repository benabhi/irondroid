package com.irondroid.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.irondroid.data.entity.HistoryEntryKind
import com.irondroid.engine.character.Character
import com.irondroid.engine.debility.Debilities
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrackType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Saves a campaign in a database file on the phone, closes it and reads it back with the device's own SQLite. */
@RunWith(AndroidJUnit4::class)
class DeviceDatabaseTest {
    @Test
    fun savedCampaignSurvivesReopeningOnTheDevice() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "device-test.db"
        context.deleteDatabase(name)
        val sheet = Character.create(edge = 3, heart = 2, iron = 2, shadow = 1, wits = 1)
            .startTrack(ProgressTrackType.VOW, "Avenge my brother", ChallengeRank.EXTREME).second
            .withDebilities(Debilities().mark(Debility.SHAKEN))

        val first = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        val campaignId = CampaignStore(first).run {
            val id = createCampaign(createWorld("Ironlands"), "Device test", "Kira", sheet)
            appendTurn(id, "I swear", "The iron is cold.", listOf(NewHistoryEntry(HistoryEntryKind.MOVE, "{}")))
            id
        }
        first.close()

        val reopened = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        try {
            val store = CampaignStore(reopened)
            assertEquals(sheet, store.loadCharacter(campaignId)!!.sheet)
            assertEquals(1, store.history(campaignId).entries.size)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
