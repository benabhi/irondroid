package com.irondroid.data

import androidx.room.withTransaction
import com.irondroid.data.dao.CampaignSummary
import com.irondroid.data.entity.ActionCallEntity
import com.irondroid.data.entity.CampaignEntity
import com.irondroid.data.entity.CampaignStatus
import com.irondroid.data.entity.CharacterGender
import com.irondroid.data.entity.HistoryEntryEntity
import com.irondroid.data.entity.HistoryEntryKind
import com.irondroid.data.entity.TurnEntity
import com.irondroid.data.entity.WorldEntity
import com.irondroid.data.entity.WorldTruthEntity
import com.irondroid.engine.action.CallOutcome
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.UndoRole
import com.irondroid.engine.character.Character
import kotlinx.coroutines.flow.Flow

/** A truth to save with a new world; [optionId] is null for one the player wrote. */
data class WorldTruth(val truthKey: String, val optionId: String?, val text: String)

/** A world with the characters of its campaigns, oldest first, to tell worlds apart. */
data class WorldSummary(val id: Long, val name: String, val characterNames: List<String>)

/** A saved character: its name and the engine sheet. */
data class SavedCharacter(val id: Long, val campaignId: Long, val name: String, val sheet: Character)

/** A history card to save; [payload] is JSON made by whoever creates the card. */
data class NewHistoryEntry(val kind: HistoryEntryKind, val payload: String)

/** A call to the actions interface to save in the campaign's log; JSON fields as in [ActionCallEntity]. */
data class NewActionCall(
    val sequence: Int,
    val actionName: String,
    val caller: Caller,
    val arguments: String,
    val outcome: CallOutcome,
    val details: String,
    val undoRole: UndoRole,
    val before: String?,
    val choiceOpenAfter: Boolean,
    val undone: Boolean = false,
)

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

    /** Creates a world with its truths and returns its id. World names are unique, ignoring case. */
    suspend fun createWorld(name: String, truths: List<WorldTruth> = emptyList()): Long = database.withTransaction {
        require(name.isNotBlank()) { "A world needs a name" }
        require(worlds.countNamed(name.trim()) == 0) { "A world named \"${name.trim()}\" already exists" }
        val worldId = worlds.insert(WorldEntity(name = name.trim(), createdAt = clock()))
        worlds.insertTruths(truths.map { WorldTruthEntity(worldId, it.truthKey, it.optionId, it.text) })
        worldId
    }

    suspend fun worlds(): List<WorldEntity> = worlds.all()

    suspend fun world(worldId: Long): WorldEntity? = worlds.get(worldId)

    /** Every world, oldest first, with who lives in it. */
    suspend fun worldSummaries(): List<WorldSummary> =
        worlds.residents().groupBy { it.worldId }.map { (worldId, rows) ->
            WorldSummary(worldId, rows.first().worldName, rows.mapNotNull { it.characterName })
        }

    /** Deletes a world with its truths. Refuses a world that still has campaigns, so no game is lost (IRON-119). */
    suspend fun deleteWorld(worldId: Long) = database.withTransaction {
        check(worlds.countCampaigns(worldId) == 0) { "World $worldId still has campaigns" }
        worlds.delete(worldId)
    }

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

    /** Creates a new world with its [truths] and starts a campaign in it, all or nothing. Returns the campaign id. */
    suspend fun createCampaignInNewWorld(
        worldName: String,
        title: String,
        characterName: String,
        sheet: Character,
        truths: List<WorldTruth> = emptyList(),
    ): Long = database.withTransaction { createCampaign(createWorld(worldName, truths), title, characterName, sheet) }

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

    /** How the narrator refers to the campaign's character; null when the player has not said (IRON-56). */
    suspend fun characterGender(campaignId: Long): CharacterGender? = characters.forCampaign(campaignId)?.gender

    suspend fun setCharacterGender(campaignId: Long, gender: CharacterGender?) = database.withTransaction {
        val saved = checkNotNull(characters.forCampaign(campaignId)) { "Campaign $campaignId has no character" }
        characters.update(saved.copy(gender = gender))
    }

    /** Replaces the saved sheet of the campaign's character with [sheet]. */
    suspend fun saveCharacter(campaignId: Long, sheet: Character) = database.withTransaction {
        val saved = checkNotNull(characters.forCampaign(campaignId)) { "Campaign $campaignId has no character" }
        characters.update(CharacterMapper.toEntity(sheet, campaignId, saved.name, saved.id).copy(gender = saved.gender))
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
        metrics: String? = null,
    ): Long = database.withTransaction {
        val now = clock()
        val turnId = history.insertTurn(
            TurnEntity(
                campaignId = campaignId,
                number = history.lastTurnNumber(campaignId) + 1,
                playerInput = playerInput,
                narration = narration,
                createdAt = now,
                metrics = metrics,
            ),
        )
        insertEntries(campaignId, turnId, entries, now)
        campaigns.touch(campaignId, now)
        turnId
    }

    /**
     * Deletes the last turn with its cards (RF-ENV-09), after its calls were undone. Returns whether
     * there was one. The calls stay in the log, marked undone.
     */
    suspend fun deleteLastTurn(campaignId: Long): Boolean = database.withTransaction {
        val deleted = history.deleteLastTurn(campaignId) > 0
        if (deleted) campaigns.touch(campaignId, clock())
        deleted
    }

    /** Number of the last turn played, 0 before the first. */
    suspend fun lastTurnNumber(campaignId: Long): Int = history.lastTurnNumber(campaignId)

    /** Sequence of the first call of turn [number] that is not undone, or null when it made none. */
    suspend fun firstCallOfTurn(campaignId: Long, number: Int): Int? =
        history.calls(campaignId).filter { it.turnNumber == number && !it.undone }.minOfOrNull { it.sequence }

    /** Saves cards that happened outside a turn, such as a manual sheet edit. */
    suspend fun appendEntries(campaignId: Long, entries: List<NewHistoryEntry>) = database.withTransaction {
        val now = clock()
        insertEntries(campaignId, null, entries, now)
        campaigns.touch(campaignId, now)
    }

    suspend fun history(campaignId: Long): CampaignHistory = database.withTransaction {
        CampaignHistory(history.turns(campaignId), history.entries(campaignId))
    }

    /** Saves a call to the actions interface in the turn being played (RF-API-09). */
    suspend fun appendActionCall(campaignId: Long, call: NewActionCall) = database.withTransaction {
        history.insertCall(
            ActionCallEntity(
                campaignId = campaignId,
                sequence = call.sequence,
                turnNumber = history.lastTurnNumber(campaignId) + 1,
                actionName = call.actionName,
                caller = call.caller,
                arguments = call.arguments,
                outcome = call.outcome,
                details = call.details,
                undoRole = call.undoRole,
                before = call.before,
                choiceOpenAfter = call.choiceOpenAfter,
                undone = call.undone,
                createdAt = clock(),
            ),
        )
    }

    /** Marks the calls with [sequences] as taken back by undo (RF-API-10). */
    suspend fun markActionCallsUndone(campaignId: Long, sequences: List<Int>) = history.markCallsUndone(campaignId, sequences)

    /** Every call to the actions interface in the campaign, oldest first. */
    suspend fun actionCalls(campaignId: Long): List<ActionCallEntity> = history.calls(campaignId)

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
