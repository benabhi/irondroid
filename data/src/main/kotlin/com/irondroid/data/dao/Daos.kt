package com.irondroid.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.irondroid.data.entity.ActionCallEntity
import com.irondroid.data.entity.CampaignEntity
import com.irondroid.data.entity.CampaignStatus
import com.irondroid.data.entity.CharacterEntity
import com.irondroid.data.entity.HistoryEntryEntity
import com.irondroid.data.entity.PendingBonusEntity
import com.irondroid.data.entity.ProgressTrackEntity
import com.irondroid.data.entity.TurnEntity
import com.irondroid.data.entity.WorldEntity
import com.irondroid.data.entity.WorldTruthEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldDao {
    @Insert
    suspend fun insert(world: WorldEntity): Long

    @Insert
    suspend fun insertTruths(truths: List<WorldTruthEntity>)

    @Query("SELECT * FROM worlds WHERE id = :id")
    suspend fun get(id: Long): WorldEntity?

    @Query("SELECT * FROM worlds ORDER BY createdAt, id")
    suspend fun all(): List<WorldEntity>

    /** One row per campaign of each world, or one row with no character for a world without campaigns. */
    @Query(
        """
        SELECT w.id AS worldId, w.name AS worldName, ch.name AS characterName
        FROM worlds w
        LEFT JOIN campaigns c ON c.worldId = w.id
        LEFT JOIN characters ch ON ch.campaignId = c.id
        ORDER BY w.createdAt, w.id, c.createdAt, c.id
        """,
    )
    suspend fun residents(): List<WorldResidentRow>

    @Query("SELECT COUNT(*) FROM worlds WHERE name = :name COLLATE NOCASE")
    suspend fun countNamed(name: String): Int

    @Query("SELECT * FROM world_truths WHERE worldId = :worldId ORDER BY truthKey")
    suspend fun truths(worldId: Long): List<WorldTruthEntity>

    @Query("SELECT COUNT(*) FROM campaigns WHERE worldId = :worldId")
    suspend fun countCampaigns(worldId: Long): Int

    /** Deletes the world; its truths go with it (cascade). */
    @Query("DELETE FROM worlds WHERE id = :worldId")
    suspend fun delete(worldId: Long)
}

/** A world and one character living in it, or null when the world has no campaigns. */
data class WorldResidentRow(val worldId: Long, val worldName: String, val characterName: String?)

/** A campaign as the campaign list shows it. */
data class CampaignSummary(
    val id: Long,
    val worldId: Long,
    val title: String,
    val status: CampaignStatus,
    val updatedAt: Long,
    val characterName: String?,
)

@Dao
interface CampaignDao {
    @Insert
    suspend fun insert(campaign: CampaignEntity): Long

    @Query("SELECT * FROM campaigns WHERE id = :id")
    suspend fun get(id: Long): CampaignEntity?

    @Query(
        """
        SELECT c.id, c.worldId, c.title, c.status, c.updatedAt, ch.name AS characterName
        FROM campaigns c LEFT JOIN characters ch ON ch.campaignId = c.id
        ORDER BY c.updatedAt DESC, c.id DESC
        """,
    )
    fun observeSummaries(): Flow<List<CampaignSummary>>

    @Query("UPDATE campaigns SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE campaigns SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: CampaignStatus, updatedAt: Long)

    @Query("DELETE FROM campaigns WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CharacterDao {
    @Insert
    suspend fun insert(character: CharacterEntity): Long

    @Update
    suspend fun update(character: CharacterEntity)

    @Query("SELECT * FROM characters WHERE campaignId = :campaignId")
    suspend fun forCampaign(campaignId: Long): CharacterEntity?

    @Query("SELECT * FROM progress_tracks WHERE characterId = :characterId ORDER BY trackId")
    suspend fun tracks(characterId: Long): List<ProgressTrackEntity>

    @Insert
    suspend fun insertTracks(tracks: List<ProgressTrackEntity>)

    @Query("DELETE FROM progress_tracks WHERE characterId = :characterId")
    suspend fun deleteTracks(characterId: Long)

    @Query("SELECT * FROM pending_bonuses WHERE characterId = :characterId ORDER BY position")
    suspend fun pendingBonuses(characterId: Long): List<PendingBonusEntity>

    @Insert
    suspend fun insertPendingBonuses(bonuses: List<PendingBonusEntity>)

    @Query("DELETE FROM pending_bonuses WHERE characterId = :characterId")
    suspend fun deletePendingBonuses(characterId: Long)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertTurn(turn: TurnEntity): Long

    @Insert
    suspend fun insertEntries(entries: List<HistoryEntryEntity>)

    @Query("SELECT COALESCE(MAX(number), 0) FROM turns WHERE campaignId = :campaignId")
    suspend fun lastTurnNumber(campaignId: Long): Int

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM history_entries WHERE campaignId = :campaignId")
    suspend fun lastSequence(campaignId: Long): Int

    /** Deletes the last turn of the campaign; its history cards go with it. */
    @Query("DELETE FROM turns WHERE campaignId = :campaignId AND number = (SELECT MAX(number) FROM turns WHERE campaignId = :campaignId)")
    suspend fun deleteLastTurn(campaignId: Long): Int

    @Query("SELECT * FROM turns WHERE campaignId = :campaignId ORDER BY number")
    suspend fun turns(campaignId: Long): List<TurnEntity>

    @Query("SELECT * FROM history_entries WHERE campaignId = :campaignId ORDER BY sequence")
    suspend fun entries(campaignId: Long): List<HistoryEntryEntity>

    @Insert
    suspend fun insertCall(call: ActionCallEntity): Long

    @Query("UPDATE action_calls SET undone = 1 WHERE campaignId = :campaignId AND sequence IN (:sequences)")
    suspend fun markCallsUndone(campaignId: Long, sequences: List<Int>)

    @Query("SELECT * FROM action_calls WHERE campaignId = :campaignId ORDER BY sequence")
    suspend fun calls(campaignId: Long): List<ActionCallEntity>
}
