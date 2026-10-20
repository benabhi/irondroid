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
    data class Action(val sources: List<RollSource>, val bonuses: List<RollBonus> = emptyList()) : MoveRoll

    /** Progress roll on a track of type [track]. */
    @Serializable
    @SerialName("progress")
    data class Progress(val track: ProgressTrackType) : MoveRoll

    /** No dice: the move just applies its effects. */
    @Serializable
    @SerialName("none")
    data object None : MoveRoll
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

    /** Another move follows, such as Pay the Price or Endure Harm with an [amount]. */
    @Serializable
    @SerialName("trigger_move")
    data class TriggerMove(val move: String, val amount: Int? = null) : Effect
}

/** One option of a choice, with the effects it applies when picked. */
@Serializable
data class MoveOption(val id: String, val effects: List<Effect> = emptyList())

/** The player picks [count] of [options]. */
@Serializable
data class Choice(val count: Int, val options: List<MoveOption>)

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
 * One move. Rolled moves need [outcomes]; moves without a roll apply [effects] directly.
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
    val track: ProgressTrackType? = null,
    @SerialName("blocked_by") val blockedBy: List<Debility> = emptyList(),
) {
    /** Choices of every outcome. The same option may appear in several, like Make Camp's list. */
    val choices: List<Choice>
        get() = listOfNotNull(outcomes?.strongHit, outcomes?.weakHit, outcomes?.miss).mapNotNull { it.choice }

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
        get() = effects + listOfNotNull(outcomes?.strongHit, outcomes?.weakHit, outcomes?.miss).flatMap { outcome ->
            outcome.effects + outcome.choice?.options.orEmpty().flatMap { it.effects }
        }
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
