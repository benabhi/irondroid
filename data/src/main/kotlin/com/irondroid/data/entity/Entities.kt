package com.irondroid.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.irondroid.engine.action.CallOutcome
import com.irondroid.engine.action.Caller
import com.irondroid.engine.action.UndoRole
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrackType

// Enums are stored by name: renaming a constant needs a migration.

/**
 * A world: the setting several campaigns can share (continuous world, RF-PAR-04). Its truths live
 * in [WorldTruthEntity]; places and other shared facts will hang from it too.
 */
@Entity(tableName = "worlds")
data class WorldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** One truth of a world (RF-CAM-01). [optionId] is the book option chosen, or null for a truth the player wrote. */
@Entity(
    tableName = "world_truths",
    primaryKeys = ["worldId", "truthKey"],
    foreignKeys = [ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = ForeignKey.CASCADE)],
)
data class WorldTruthEntity(
    val worldId: Long,
    val truthKey: String,
    val optionId: String?,
    val text: String,
)

/** How a campaign stands: still being played, or ended with its character (RF-PAR-04). */
enum class CampaignStatus {
    ACTIVE,

    /** Ended by Face Death. */
    DEAD,

    /** Ended by Face Desolation. */
    DESOLATE,

    /** The character retired and wrote their epilogue. */
    RETIRED,
}

/** One story in a world, played by one character. */
@Entity(
    tableName = "campaigns",
    foreignKeys = [ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("worldId")],
)
data class CampaignEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val worldId: Long,
    val title: String,
    val status: CampaignStatus = CampaignStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The character sheet of a campaign; its progress tracks and pending bonuses are in their own tables. */
@Entity(
    tableName = "characters",
    foreignKeys = [ForeignKey(CampaignEntity::class, ["id"], ["campaignId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("campaignId", unique = true)],
)
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val campaignId: Long,
    val name: String,
    val edge: Int,
    val heart: Int,
    val iron: Int,
    val shadow: Int,
    val wits: Int,
    val health: Int,
    val spirit: Int,
    val supply: Int,
    val momentum: Int,
    val debilities: Set<Debility>,
    val experience: Int,
    val isEnded: Boolean,
    /** How the narrator refers to the character (IRON-56); null until the player says. */
    val gender: CharacterGender? = null,
)

/** Grammatical gender the narrator uses for a character. */
enum class CharacterGender { FEMININE, MASCULINE, NEUTRAL }

/** A progress track of a character, keyed by the id the engine gives it (for example `vow_1`). */
@Entity(
    tableName = "progress_tracks",
    primaryKeys = ["characterId", "trackId"],
    foreignKeys = [ForeignKey(CharacterEntity::class, ["id"], ["characterId"], onDelete = ForeignKey.CASCADE)],
)
data class ProgressTrackEntity(
    val characterId: Long,
    val trackId: String,
    val name: String,
    val type: ProgressTrackType,
    val rank: ChallengeRank?,
    val ticks: Int,
)

/** A bonus waiting for a roll, in the order the engine keeps them. */
@Entity(
    tableName = "pending_bonuses",
    primaryKeys = ["characterId", "position"],
    foreignKeys = [ForeignKey(CharacterEntity::class, ["id"], ["characterId"], onDelete = ForeignKey.CASCADE)],
)
data class PendingBonusEntity(
    val characterId: Long,
    val position: Int,
    val amount: Int,
    val move: String?,
)

/** One turn of play: what the player said and how the narrator answered. Numbered from 1 in each campaign. */
@Entity(
    tableName = "turns",
    foreignKeys = [ForeignKey(CampaignEntity::class, ["id"], ["campaignId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("campaignId", "number", unique = true)],
)
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val campaignId: Long,
    val number: Int,
    val playerInput: String?,
    val narration: String?,
    val createdAt: Long,
)

/** What a history card shows (RF-NAR-03). */
enum class HistoryEntryKind {
    /** A move with its roll and outcome. */
    MOVE,

    /** An oracle question or table roll. */
    ORACLE,

    /** A change to the sheet, made by a move or by hand (RF-PJ-04). */
    SHEET_CHANGE,

    /** A free note. */
    NOTE,
}

/**
 * A card in the campaign history, in [sequence] order. [turnId] is the turn it happened in, or null
 * for something done outside a turn. [payload] is JSON written by the layer that makes the card.
 */
@Entity(
    tableName = "history_entries",
    foreignKeys = [
        ForeignKey(CampaignEntity::class, ["id"], ["campaignId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TurnEntity::class, ["id"], ["turnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("campaignId", "sequence", unique = true), Index("turnId")],
)
data class HistoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val campaignId: Long,
    val turnId: Long?,
    val sequence: Int,
    val kind: HistoryEntryKind,
    val payload: String,
    val createdAt: Long,
)

/**
 * One call to the actions interface, in [sequence] order (RF-API-09): who made it, what it asked and
 * what came of it, during turn [turnNumber] (the turn being played, one more than the last saved).
 * [details] is the result or error as JSON. [before] is the character sheet as JSON just before a
 * call that starts an undo unit; [undone] marks calls taken back with undo (RF-API-10).
 */
@Entity(
    tableName = "action_calls",
    foreignKeys = [ForeignKey(CampaignEntity::class, ["id"], ["campaignId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("campaignId", "sequence", unique = true)],
)
data class ActionCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val campaignId: Long,
    val sequence: Int,
    val turnNumber: Int,
    val actionName: String,
    val caller: Caller,
    val arguments: String,
    val outcome: CallOutcome,
    val details: String,
    val undoRole: UndoRole,
    val before: String?,
    val choiceOpenAfter: Boolean,
    val undone: Boolean,
    val createdAt: Long,
)
