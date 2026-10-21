package com.irondroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.irondroid.data.entity.CampaignStatus
import com.irondroid.data.entity.HistoryEntryKind
import com.irondroid.engine.character.Character
import com.irondroid.engine.character.PendingBonus
import com.irondroid.engine.debility.Debilities
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrackType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CampaignStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: IrondroidDatabase
    private lateinit var store: CampaignStore
    private var now = 1_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, IrondroidDatabase::class.java).build()
        store = CampaignStore(database) { now }
    }

    @After
    fun tearDown() = database.close()

    /** A sheet that uses every saved field: stats, meters, momentum, debilities, tracks, bonuses and experience. */
    private fun playedSheet(): Character {
        var sheet = Character.create(edge = 3, heart = 2, iron = 2, shadow = 1, wits = 1)
        sheet = sheet.startTrack(ProgressTrackType.VOW, "Avenge my brother", ChallengeRank.EXTREME).second
        sheet = sheet.startTrack(ProgressTrackType.BONDS, "Bonds", null).second
        val (journeyId, withJourney) = sheet.startTrack(ProgressTrackType.JOURNEY, "To the Havens", ChallengeRank.DANGEROUS)
        sheet = withJourney.copy(
            tracks = withJourney.tracks + (journeyId to withJourney.tracks.getValue(journeyId).markProgress(3)),
        )
        sheet = sheet.copy(
            meters = sheet.meters.lose(MeterType.HEALTH, 2).first.lose(MeterType.SUPPLY, 4).first,
            momentum = sheet.momentum.gain(5),
            experience = 4,
            pendingBonuses = listOf(PendingBonus(1), PendingBonus(2, "gather_information")),
        )
        return sheet.withDebilities(Debilities().mark(Debility.WOUNDED).mark(Debility.CURSED))
    }

    private suspend fun newCampaign(sheet: Character = playedSheet(), worldId: Long? = null): Long =
        store.createCampaign(worldId ?: store.createWorld("Ironlands"), "The Iron Vow", "Kira", sheet)

    @Test
    fun `a new campaign loads back with the same character`() = runTest {
        val sheet = playedSheet()
        val campaignId = newCampaign(sheet)

        val saved = store.loadCharacter(campaignId)!!

        assertEquals("Kira", saved.name)
        assertEquals(campaignId, saved.campaignId)
        assertEquals(sheet, saved.sheet)
        val campaign = store.campaign(campaignId)!!
        assertEquals("The Iron Vow", campaign.title)
        assertEquals(CampaignStatus.ACTIVE, campaign.status)
    }

    @Test
    fun `saving the character replaces sheet, tracks and bonuses`() = runTest {
        val campaignId = newCampaign()
        val before = store.loadCharacter(campaignId)!!.sheet
        val after = before.copy(
            tracks = before.tracks - "journey_1",
            pendingBonuses = listOf(PendingBonus(3, "strike")),
            experience = 0,
            isEnded = true,
        ).withDebilities(Debilities().mark(Debility.MAIMED))
        now = 5_000L

        store.saveCharacter(campaignId, after)

        assertEquals(after, store.loadCharacter(campaignId)!!.sheet)
        assertEquals(5_000L, store.campaign(campaignId)!!.updatedAt)
    }

    @Test
    fun `saving a campaign without character fails`() = runTest {
        assertFailsWith<IllegalStateException> { store.saveCharacter(99, playedSheet()) }
        assertNull(store.loadCharacter(99))
    }

    @Test
    fun `turns are numbered and history cards keep play order`() = runTest {
        val campaignId = newCampaign()
        val first = store.appendTurn(
            campaignId,
            "I search the cabin",
            "The door creaks open.",
            listOf(NewHistoryEntry(HistoryEntryKind.MOVE, """{"move":"gather_information"}""")),
        )
        store.appendEntries(campaignId, listOf(NewHistoryEntry(HistoryEntryKind.SHEET_CHANGE, """{"health":5}""")))
        val second = store.appendTurn(
            campaignId,
            "Is anyone inside?",
            "Silence answers you.",
            listOf(
                NewHistoryEntry(HistoryEntryKind.ORACLE, """{"answer":"no"}"""),
                NewHistoryEntry(HistoryEntryKind.NOTE, """{"text":"Empty"}"""),
            ),
        )

        val history = store.history(campaignId)

        assertEquals(listOf(1, 2), history.turns.map { it.number })
        assertEquals("I search the cabin", history.turns[0].playerInput)
        assertEquals("Silence answers you.", history.turns[1].narration)
        assertEquals(listOf(1, 2, 3, 4), history.entries.map { it.sequence })
        assertEquals(listOf(first, null, second, second), history.entries.map { it.turnId })
        assertEquals(
            listOf(HistoryEntryKind.MOVE, HistoryEntryKind.SHEET_CHANGE, HistoryEntryKind.ORACLE, HistoryEntryKind.NOTE),
            history.entries.map { it.kind },
        )
    }

    @Test
    fun `turn numbers are counted per campaign`() = runTest {
        val worldId = store.createWorld("Ironlands")
        val one = newCampaign(worldId = worldId)
        val two = newCampaign(worldId = worldId)
        store.appendTurn(one, "a", "b")
        store.appendTurn(one, "c", "d")

        store.appendTurn(two, null, "The story begins.")

        assertEquals(listOf(1), store.history(two).turns.map { it.number })
    }

    @Test
    fun `deleting a campaign removes its character and history but keeps the world`() = runTest {
        val worldId = store.createWorld("Ironlands")
        val ended = newCampaign(worldId = worldId)
        val next = newCampaign(worldId = worldId)
        store.appendTurn(ended, "I fall", "Darkness takes you.", listOf(NewHistoryEntry(HistoryEntryKind.MOVE, "{}")))

        store.deleteCampaign(ended)

        assertNull(store.campaign(ended))
        assertNull(store.loadCharacter(ended))
        assertEquals(CampaignHistory(emptyList(), emptyList()), store.history(ended))
        assertEquals(listOf(worldId), store.worlds().map { it.id })
        assertEquals("Kira", store.loadCharacter(next)!!.name)
    }

    @Test
    fun `the campaign list shows the most recent first with its character and status`() = runTest {
        val older = newCampaign()
        now = 2_000L
        val newer = store.createCampaign(store.createWorld("Another world"), "Second", "Arn", playedSheet())

        now = 3_000L
        store.setStatus(older, CampaignStatus.RETIRED)
        val summaries = store.observeCampaigns().first()

        assertEquals(listOf(older, newer), summaries.map { it.id })
        assertEquals(listOf("Kira", "Arn"), summaries.map { it.characterName })
        assertEquals(CampaignStatus.RETIRED, summaries[0].status)
        assertEquals(3_000L, summaries[0].updatedAt)
    }

    @Test
    fun `world truths are saved with chosen and written ones`() = runTest {
        val truths = listOf(
            WorldTruth("cataclysm", "cataclysm_2", "We fled the sickness."),
            WorldTruth("iron", null, "Iron sings to those who swear on it."),
        )

        val worldId = store.createWorld("Ironlands", truths)

        assertEquals(truths.sortedBy { it.truthKey }, store.truths(worldId))
        assertEquals("Ironlands", store.worlds().single().name)
    }

    @Test
    fun `a saved campaign survives closing and reopening the database file`() = runTest {
        val name = "reopen-test.db"
        context.deleteDatabase(name)
        val sheet = playedSheet()
        val fileDb = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        val campaignId = CampaignStore(fileDb) { now }.let { fileStore ->
            val id = fileStore.createCampaign(fileStore.createWorld("Ironlands"), "Saved", "Kira", sheet)
            fileStore.appendTurn(id, "hello", "world")
            id
        }
        fileDb.close()

        val reopened = Room.databaseBuilder(context, IrondroidDatabase::class.java, name).build()
        try {
            val reopenedStore = CampaignStore(reopened)
            assertEquals(sheet, reopenedStore.loadCharacter(campaignId)!!.sheet)
            assertEquals("world", reopenedStore.history(campaignId).turns.single().narration)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `a campaign can start in a new world in one step`() = runTest {
        val campaignId = store.createCampaignInNewWorld("Ironlands", "First", "Kira", playedSheet())

        val campaign = store.campaign(campaignId)!!
        assertEquals("Ironlands", store.world(campaign.worldId)!!.name)
        assertEquals("Kira", store.loadCharacter(campaignId)!!.name)
        assertNull(store.world(campaign.worldId + 1))
    }

    @Test
    fun `two worlds cannot share a name`() = runTest {
        store.createWorld("Ironlands")

        assertFailsWith<IllegalArgumentException> { store.createWorld("  ironlands ") }
        assertFailsWith<IllegalArgumentException> { store.createCampaignInNewWorld("IRONLANDS", "T", "K", playedSheet()) }
        assertFailsWith<IllegalArgumentException> { store.createWorld(" ") }
        assertEquals(1, store.worlds().size)
        assertEquals(emptyList(), store.observeCampaigns().first())
    }

    @Test
    fun `world summaries list who lives in each world`() = runTest {
        val shared = store.createWorld("Ironlands")
        store.createCampaign(shared, "First", "Kira", playedSheet())
        store.createCampaign(shared, "Second", "Arn", playedSheet())
        val empty = store.createWorld("Empty lands")

        assertEquals(
            listOf(WorldSummary(shared, "Ironlands", listOf("Kira", "Arn")), WorldSummary(empty, "Empty lands", emptyList())),
            store.worldSummaries(),
        )
    }

    @Test
    fun `the default clock stamps real time`() = runTest {
        val realStore = CampaignStore(database)
        val start = System.currentTimeMillis()

        val worldId = realStore.createWorld("Ironlands")

        assertTrue(realStore.worlds().single { it.id == worldId }.createdAt >= start)
    }
}
