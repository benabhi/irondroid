package com.irondroid.engine.move

import com.irondroid.engine.character.Stat
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
    /** Action roll adding one of [sources], chosen by the player or the narrator. */
    @Serializable
    @SerialName("action")
    data class Action(val sources: List<RollSource>) : MoveRoll

    /** Progress roll on a track of type [track]. */
    @Serializable
    @SerialName("progress")
    data class Progress(val track: ProgressTrackType) : MoveRoll

    /** No dice: the move just applies its effects. */
    @Serializable
    @SerialName("none")
    data object None : MoveRoll
}

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

    /** Bonus added to the next roll. */
    @Serializable
    @SerialName("next_roll_bonus")
    data class NextRollBonus(val amount: Int) : Effect

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
 */
@Serializable
data class MoveDefinition(
    val id: String,
    val category: MoveCategory,
    val roll: MoveRoll,
    val outcomes: MoveOutcomes? = null,
    val effects: List<Effect> = emptyList(),
) {
    /** Every option id of every outcome. */
    val optionIds: List<String>
        get() = listOfNotNull(outcomes?.strongHit, outcomes?.weakHit, outcomes?.miss)
            .flatMap { it.choice?.options.orEmpty() }
            .map { it.id }

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
)

/** Root of moves.<language>.json. */
@Serializable
internal data class MoveTextFile(val version: Int, val moves: Map<String, MoveText>)
