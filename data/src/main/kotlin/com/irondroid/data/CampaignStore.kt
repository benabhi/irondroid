package com.irondroid.data

import androidx.room.withTransaction
import com.irondroid.data.dao.CampaignSummary
import com.irondroid.data.entity.CampaignEntity
import com.irondroid.data.entity.CampaignStatus
import com.irondroid.data.entity.HistoryEntryEntity
import com.irondroid.data.entity.HistoryEntryKind
import com.irondroid.data.entity.TurnEntity
import com.irondroid.data.entity.WorldEntity
import com.irondroid.data.entity.WorldTruthEntity
import com.irondroid.engine.character.Character
import kotlinx.coroutines.flow.Flow

/** A truth to save with a new world; [optionId] is null for one the player wrote. */
data class WorldTruth(val truthKey: String, val optionId: String?, val text: String)

/** A saved character: its name and the engine sheet. */
data class SavedCharacter(val id: Long, val campaignId: Long, val name: String, val sheet: Character)

/** A history card to save; [payload] is JSON made by whoever creates the card. */
data class NewHistoryEntry(val kind: HistoryEntryKind, val payload: String)

/** A campaign's whole history: its turns and every card, each list in play order. */
data class CampaignHistory(val turns: List<TurnEntity>, val entries: List<HistoryEntryEntity>)

/**
 * Saves and loads worlds, campaigns, characters and history. Every write that touches several
 * tables runs in one transaction, so a campaign is never left half saved.
 */
class CampaignStore(
    private val database: IrondroidDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val worlds = database.worlds()
    private val campaigns = database.campaigns()
    private val characters = database.characters()
    private val history = database.history()

    /** Creates a world with its truths and returns its id. */
    suspend fun createWorld(name: String, truths: List<WorldTruth> = emptyList()): Long = database.withTransaction {
        val worldId = worlds.insert(WorldEntity(name = name, createdAt = clock()))
        worlds.insertTruths(truths.map { WorldTruthEntity(worldId, it.truthKey, it.optionId, it.text) })
        worldId
    }

    suspend fun worlds(): List<WorldEntity> = worlds.all()

    suspend fun world(worldId: Long): WorldEntity? = worlds.get(worldId)

    suspend fun truths(worldId: Long): List<WorldTruth> =
        worlds.truths(worldId).map { WorldTruth(it.truthKey, it.optionId, it.text) }

    /** Starts a campaign in [worldId] with its character and returns the campaign id. */
    suspend fun createCampaign(worldId: Long, title: String, characterName: String, sheet: Character): Long =
        database.withTransaction {
            val now = clock()
            val campaignId = campaigns.insert(
                CampaignEntity(worldId = worldId, title = title, createdAt = now, updatedAt = now),
            )
            val characterId = characters.insert(CharacterMapper.toEntity(sheet, campaignId, characterName))
            characters.insertTracks(CharacterMapper.tracksToEntities(sheet, characterId))
            characters.insertPendingBonuses(CharacterMapper.bonusesToEntities(sheet, characterId))
            campaignId
        }

    /** Creates a new world and starts a campaign in it, both or neither. Returns the campaign id. */
    suspend fun createCampaignInNewWorld(worldName: String, title: String, characterName: String, sheet: Character): Long =
        database.withTransaction { createCampaign(createWorld(worldName), title, characterName, sheet) }

    suspend fun campaign(campaignId: Long): CampaignEntity? = campaigns.get(campaignId)

    /** Every campaign, most recently played first, updated whenever one changes. */
    fun observeCampaigns(): Flow<List<CampaignSummary>> = campaigns.observeSummaries()

    suspend fun setStatus(campaignId: Long, status: CampaignStatus) = campaigns.setStatus(campaignId, status, clock())

    /** Deletes a campaign with its character and history. The world stays for other campaigns. */
    suspend fun deleteCampaign(campaignId: Long) = campaigns.delete(campaignId)

    suspend fun loadCharacter(campaignId: Long): SavedCharacter? = database.withTransaction {
        characters.forCampaign(campaignId)?.let { entity ->
            val sheet = CharacterMapper.toSheet(
                entity,
                characters.tracks(entity.id),
                characters.pendingBonuses(entity.id),
            )
            SavedCharacter(entity.id, campaignId, entity.name, sheet)
        }
    }

    /** Replaces the saved sheet of the campaign's character with [sheet]. */
    suspend fun saveCharacter(campaignId: Long, sheet: Character) = database.withTransaction {
        val saved = checkNotNull(characters.forCampaign(campaignId)) { "Campaign $campaignId has no character" }
        characters.update(CharacterMapper.toEntity(sheet, campaignId, saved.name, saved.id))
        characters.deleteTracks(saved.id)
        characters.insertTracks(CharacterMapper.tracksToEntities(sheet, saved.id))
        characters.deletePendingBonuses(saved.id)
        characters.insertPendingBonuses(CharacterMapper.bonusesToEntities(sheet, saved.id))
        campaigns.touch(campaignId, clock())
    }

    /** Saves the next turn of the campaign with the cards it produced, and returns the turn id. */
    suspend fun appendTurn(
        campaignId: Long,
        playerInput: String?,
        narration: String?,
        entries: List<NewHistoryEntry> = emptyList(),
    ): Long = database.withTransaction {
        val now = clock()
        val turnId = history.insertTurn(
            TurnEntity(
                campaignId = campaignId,
                number = history.lastTurnNumber(campaignId) + 1,
                playerInput = playerInput,
                narration = narration,
                createdAt = now,
            ),
        )
        insertEntries(campaignId, turnId, entries, now)
        campaigns.touch(campaignId, now)
        turnId
    }

    /** Saves cards that happened outside a turn, such as a manual sheet edit. */
    suspend fun appendEntries(campaignId: Long, entries: List<NewHistoryEntry>) = database.withTransaction {
        val now = clock()
        insertEntries(campaignId, null, entries, now)
        campaigns.touch(campaignId, now)
    }

    suspend fun history(campaignId: Long): CampaignHistory = database.withTransaction {
        CampaignHistory(history.turns(campaignId), history.entries(campaignId))
    }

    private suspend fun insertEntries(campaignId: Long, turnId: Long?, entries: List<NewHistoryEntry>, now: Long) {
        val first = history.lastSequence(campaignId) + 1
        history.insertEntries(
            entries.mapIndexed { index, entry ->
                HistoryEntryEntity(
                    campaignId = campaignId,
                    turnId = turnId,
                    sequence = first + index,
                    kind = entry.kind,
                    payload = entry.payload,
                    createdAt = now,
                )
            },
        )
    }
}
