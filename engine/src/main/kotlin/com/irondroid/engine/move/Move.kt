package com.irondroid.engine.move

import com.irondroid.engine.character.Stat
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.progress.ProgressTrackType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Moves as data (RF-MOT-10, RNF-MAN-02). These classes mirror moves.json: structure only.
 * Every text the player sees lives in the per-language files (see MoveText).
 */

@Serializable
enum class MoveCategory {
    @SerialName("adventure") ADVENTURE,
    @SerialName("relationship") RELATIONSHIP,
    @SerialName("combat") COMBAT,
    @SerialName("suffer") SUFFER,
    @SerialName("quest") QUEST,
    @SerialName("fate") FATE,
}

/** What an action roll adds to the d6: a stat, or a condition meter for moves such as Heal or Endure Harm. */
@Serializable
enum class RollSource(val stat: Stat?, val meter: MeterType?) {
    @SerialName("edge") EDGE(Stat.EDGE, null),
    @SerialName("heart") HEART(Stat.HEART, null),
    @SerialName("iron") IRON(Stat.IRON, null),
    @SerialName("shadow") SHADOW(Stat.SHADOW, null),
    @SerialName("wits") WITS(Stat.WITS, null),
    @SerialName("health") HEALTH(null, MeterType.HEALTH),
    @SerialName("spirit") SPIRIT(null, MeterType.SPIRIT),
    @SerialName("supply") SUPPLY(null, MeterType.SUPPLY),
}

/** How a move is rolled. */
@Serializable
sealed interface MoveRoll {
    /**
     * Action roll adding one of [sources], chosen by the player or the narrator.
     * [bonuses] are situational adds the narrator applies when the fiction fits, such as +1 among a community.
     */
    @Serializable
    @SerialName("action")
    data class Action(
        val sources: List<RollSource>,
        val bonuses: List<RollBonus> = emptyList(),
        val pick: SourcePick = SourcePick.ANY,
    ) : MoveRoll

    /** Progress roll on a track of type [track]. */
    @Serializable
    @SerialName("progress")
    data class Progress(val track: ProgressTrackType) : MoveRoll

    /** No dice: the move just applies its effects. */
    @Serializable
    @SerialName("none")
    data object None : MoveRoll
}

/** How the source of an action roll is picked among its sources. */
@Serializable
enum class SourcePick {
    /** The one that fits the fiction. */
    @SerialName("any") ANY,

    /** The highest value, as in Endure Harm (+health or +iron). */
    @SerialName("highest") HIGHEST,

    /** The lowest value. */
    @SerialName("lowest") LOWEST,
}

/** A situational add to an action roll; its condition is described in the move texts under [id]. */
@Serializable
data class RollBonus(val id: String, val amount: Int)

/** A change a move applies. New kinds are added here as moves need them. */
@Serializable
sealed interface Effect {
    @Serializable
    @SerialName("momentum")
    data class AdjustMomentum(val amount: Int) : Effect

    @Serializable
    @SerialName("meter")
    data class AdjustMeter(val meter: MeterType, val amount: Int) : Effect

    /** Marks progress on the track the move is about. */
    @Serializable
    @SerialName("mark_progress")
    data class MarkProgress(val times: Int = 1) : Effect

    /** Clears progress on the move's track, leaving [keepBoxes] filled boxes. */
    @Serializable
    @SerialName("clear_progress")
    data class ClearProgress(@SerialName("keep_boxes") val keepBoxes: Int = 0) : Effect

    /** Raises the rank of the move's track by one, up to epic. */
    @Serializable
    @SerialName("raise_rank")
    data object RaiseRank : Effect

    /** Clears [debility] if it is marked. */
    @Serializable
    @SerialName("clear_debility")
    data class ClearDebility(val debility: Debility) : Effect

    /** Bonus added to the next roll, or only to the next time [move] is made. */
    @Serializable
    @SerialName("next_roll_bonus")
    data class NextRollBonus(val amount: Int, val move: String? = null) : Effect

    /**
     * Another move follows, such as Pay the Price or Endure Harm with an [amount].
     * With [amountByRank] the amount is the rank of the move's track (1 troublesome to 5 epic).
     */
    @Serializable
    @SerialName("trigger_move")
    data class TriggerMove(
        val move: String,
        val amount: Int? = null,
        @SerialName("amount_by_rank") val amountByRank: Boolean = false,
    ) : Effect

    /** Starts a new track of the move's type; the player names it and sets its rank. */
    @Serializable
    @SerialName("start_track")
    data object StartTrack : Effect

    /** The move's track is finished, fulfilled or forsaken, and is removed. */
    @Serializable
    @SerialName("end_track")
    data object EndTrack : Effect

    /** Marks the experience of the track's rank (1 to 5) plus [rankOffset], never below 0. */
    @Serializable
    @SerialName("mark_experience")
    data class MarkExperience(@SerialName("rank_offset") val rankOffset: Int = 0) : Effect

    @Serializable
    @SerialName("spend_experience")
    data class SpendExperience(val amount: Int) : Effect

    /**
     * Suffers -[meter] equal to the amount the move was triggered with (Endure Harm 2 means -2 health).
     * What does not fit below 0 is suffered as -momentum.
     */
    @Serializable
    @SerialName("suffer")
    data class Suffer(val meter: MeterType) : Effect

    /** Marks [debility]. */
    @Serializable
    @SerialName("mark_debility")
    data class MarkDebility(val debility: Debility) : Effect

    /** Rolls on the oracle table [table] (1d100) and applies its result. */
    @Serializable
    @SerialName("roll_table")
    data class RollTable(val table: String) : Effect

    /** Asks the yes/no oracle with odds the player or narrator sets. */
    @Serializable
    @SerialName("ask_oracle")
    data object AskOracle : Effect

    /** The character's story ends: dead or lost. */
    @Serializable
    @SerialName("end_character")
    data object EndCharacter : Effect
}

/** One option of a choice, with the effects it applies when picked. */
@Serializable
data class MoveOption(val id: String, val effects: List<Effect> = emptyList())

/** The player picks [count] of [options]. With [whenZero], the choice applies only if that meter is at 0. */
@Serializable
data class Choice(
    val count: Int,
    val options: List<MoveOption>,
    @SerialName("when_zero") val whenZero: MeterType? = null,
)

/** What happens on one outcome: fixed [effects] plus an optional [choice]. */
@Serializable
data class OutcomeDefinition(
    val effects: List<Effect> = emptyList(),
    val choice: Choice? = null,
)

@Serializable
data class MoveOutcomes(
    @SerialName("strong_hit") val strongHit: OutcomeDefinition,
    @SerialName("weak_hit") val weakHit: OutcomeDefinition,
    val miss: OutcomeDefinition,
)

/**
 * One move. Rolled moves need [outcomes]; moves without a roll apply [effects] and [choice] directly.
 * On rolled moves, [effects] apply before rolling (Swear an Iron Vow starts its track, then rolls).
 *
 * [track] is the kind of progress track the move works on (a journey for Undertake a Journey),
 * for moves that change progress without a progress roll. [blockedBy] lists debilities that
 * prevent making the move, such as unprepared for Resupply.
 */
@Serializable
data class MoveDefinition(
    val id: String,
    val category: MoveCategory,
    val roll: MoveRoll,
    val outcomes: MoveOutcomes? = null,
    val effects: List<Effect> = emptyList(),
    val choice: Choice? = null,
    val track: ProgressTrackType? = null,
    @SerialName("blocked_by") val blockedBy: List<Debility> = emptyList(),
) {
    /** Choices of every outcome. The same option may appear in several, like Make Camp's list. */
    val choices: List<Choice>
        get() = listOfNotNull(choice) +
            listOfNotNull(outcomes?.strongHit, outcomes?.weakHit, outcomes?.miss).mapNotNull { it.choice }

    /** Every distinct option id of the move. */
    val optionIds: Set<String>
        get() = choices.flatMap { choice -> choice.options.map { it.id } }.toSet()

    /** Ids of the situational roll bonuses. */
    val bonusIds: Set<String>
        get() = (roll as? MoveRoll.Action)?.bonuses.orEmpty().map { it.id }.toSet()

    /** Track the move's progress effects apply to: [track], or the one it rolls against. */
    val progressTrack: ProgressTrackType?
        get() = track ?: (roll as? MoveRoll.Progress)?.track

    /** Every effect of the move, including those inside outcomes and options. */
    val allEffects: List<Effect>
        get() = effects + listOfNotNull(outcomes?.strongHit, outcomes?.weakHit, outcomes?.miss).flatMap { it.effects } +
            choices.flatMap { choice -> choice.options.flatMap { it.effects } }
}

/** Root of moves.json. */
@Serializable
internal data class MoveFile(val version: Int, val moves: List<MoveDefinition>)

/** Texts of one move in one language. Outcome texts are present only for rolled moves. */
@Serializable
data class MoveText(
    val name: String,
    val trigger: String,
    @SerialName("strong_hit") val strongHit: String? = null,
    @SerialName("weak_hit") val weakHit: String? = null,
    val miss: String? = null,
    val options: Map<String, String> = emptyMap(),
    val bonuses: Map<String, String> = emptyMap(),
)

/** Root of moves.<language>.json. */
@Serializable
internal data class MoveTextFile(val version: Int, val moves: Map<String, MoveText>)
