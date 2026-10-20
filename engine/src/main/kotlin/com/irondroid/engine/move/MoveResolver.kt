package com.irondroid.engine.move

import com.irondroid.engine.character.Character
import com.irondroid.engine.character.PendingBonus
import com.irondroid.engine.debility.Debility
import com.irondroid.engine.dice.Dice
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.progress.ChallengeRank
import com.irondroid.engine.progress.ProgressTrack
import com.irondroid.engine.progress.ProgressTrackType
import com.irondroid.engine.roll.ActionRoll
import com.irondroid.engine.roll.Outcome
import com.irondroid.engine.roll.ProgressRoll
import com.irondroid.engine.roll.RerollDie

/** A track to start when a move opens one, such as Swear an Iron Vow. */
data class NewTrack(val name: String, val rank: ChallengeRank)

/**
 * What the player (or the narrator) brings to a move.
 *
 * @property source stat or meter to roll, for moves that let the player pick it.
 * @property bonuses situational roll bonuses that apply, by id.
 * @property track id of the progress track the move acts on.
 * @property amount how much the move is triggered with, such as 2 for Endure Harm 2.
 * @property newTrack name and rank of the track the move starts.
 * @property rerollable dice an asset lets the player roll again on this move (RF-MOT-11).
 */
data class MoveRequest(
    val source: RollSource? = null,
    val bonuses: Set<String> = emptySet(),
    val track: String? = null,
    val amount: Int? = null,
    val newTrack: NewTrack? = null,
    val rerollable: Set<RerollDie> = emptySet(),
)

/** Something the engine does not resolve by itself; the caller chains it next. */
sealed interface FollowUp {
    /** Another move must be made, such as Pay the Price, Endure Harm 1 or Face a Setback 2. */
    data class MakeMove(val move: String, val amount: Int? = null) : FollowUp

    /** Roll on an oracle table. */
    data class RollOnTable(val table: String) : FollowUp

    /** Ask the yes/no oracle. */
    data object AskOracle : FollowUp

    /** The character's story is over. */
    data object CharacterEnded : FollowUp
}

/** The dice of a move, if it was rolled. */
sealed interface RollRecord {
    val outcome: Outcome
    val isMatch: Boolean

    data class Action(val source: RollSource, val roll: ActionRoll) : RollRecord {
        override val outcome: Outcome get() = roll.outcome
        override val isMatch: Boolean get() = roll.isMatch
    }

    data class Progress(val track: String, val roll: ProgressRoll) : RollRecord {
        override val outcome: Outcome get() = roll.outcome
        override val isMatch: Boolean get() = roll.isMatch
    }
}

/** What happened in a move so far. */
data class MoveRecord(
    val move: String,
    val roll: RollRecord? = null,
    val chosen: List<String> = emptyList(),
    val followUps: List<FollowUp> = emptyList(),
    /** Track the move acted on, including one it started. */
    val track: String? = null,
)

/** A move in progress. Each step carries the character as it is at that point. */
sealed interface MoveStep {
    val character: Character
    val record: MoveRecord

    /** The move is resolved. */
    data class Done(override val character: Character, override val record: MoveRecord) : MoveStep

    /**
     * An asset allows rerolling [rerollable] dice: the player decides with [MoveResolver.reroll]
     * before anything from the outcome is applied.
     */
    data class AwaitingReroll(
        override val character: Character,
        override val record: MoveRecord,
        val rerollable: Set<RerollDie>,
        internal val context: Context,
    ) : MoveStep

    /**
     * The action roll would improve by burning momentum, to [burnOutcome]. The player decides
     * with [MoveResolver.decideBurn]; nothing from the outcome is applied yet.
     */
    data class AwaitingBurn(
        override val character: Character,
        override val record: MoveRecord,
        val burnOutcome: Outcome,
        internal val context: Context,
    ) : MoveStep

    /** A pending choice: the player picks [Choice.count] options with [MoveResolver.choose]. */
    data class AwaitingChoice(
        override val character: Character,
        override val record: MoveRecord,
        val choice: Choice,
        internal val context: Context,
    ) : MoveStep

    /** Internal state needed to continue the move. */
    class Context internal constructor(internal val move: MoveDefinition, internal val request: MoveRequest) {
        internal fun with(request: MoveRequest) = Context(move, request)
    }
}

/**
 * Plays moves from [catalog] on a [Character] (RF-MOT-03, RF-API-07, RF-ENV-11): rolls with [dice],
 * applies effects, offers burning momentum and stops at every pending choice.
 * Rules mistakes by the caller (a missing track, a blocked move, a wrong option) throw IllegalArgumentException.
 */
class MoveResolver(private val catalog: MoveCatalog, private val dice: Dice) {

    fun make(character: Character, moveId: String, request: MoveRequest = MoveRequest()): MoveStep {
        val move = requireNotNull(catalog[moveId]) { "Unknown move $moveId" }
        require(!character.isEnded) { "The character's story is over" }
        move.blockedBy.firstOrNull { it in character.debilities }?.let {
            throw IllegalArgumentException("${move.id} cannot be made while ${it.name.lowercase()}")
        }
        request.amount?.let { require(it >= 1) { "Amount must be at least 1, was $it" } }
        (request.bonuses - move.bonusIds).firstOrNull()?.let { throw IllegalArgumentException("${move.id} has no bonus $it") }

        var state = Applied(character, MoveRecord(move.id, track = request.track), request)
        state = apply(move.effects, move, state)
        val context = MoveStep.Context(move, state.request)

        return when (val roll = move.roll) {
            is MoveRoll.None -> offerChoices(listOfNotNull(move.choice), context, state)
            is MoveRoll.Action -> {
                val (rolled, record) = rollAction(roll, move, state)
                val after = rolled.copy(record = rolled.record.copy(roll = record))
                if (state.request.rerollable.isNotEmpty()) {
                    MoveStep.AwaitingReroll(after.character, after.record, state.request.rerollable, context)
                } else {
                    afterActionRoll(context, after)
                }
            }
            is MoveRoll.Progress -> {
                val trackId = requireTrack(state.character, state.request, move, roll.track)
                val result = ProgressRoll.roll(dice, state.character.tracks.getValue(trackId))
                val after = state.copy(record = state.record.copy(roll = RollRecord.Progress(trackId, result)))
                applyOutcome(result.outcome, context, after)
            }
        }
    }

    /** Rolls again the dice in [which] (empty keeps the roll), then continues as after any action roll. */
    fun reroll(step: MoveStep.AwaitingReroll, which: Set<RerollDie>): MoveStep {
        (which - step.rerollable).firstOrNull()?.let { throw IllegalArgumentException("The asset does not allow rerolling $it") }
        val record = step.record.roll as RollRecord.Action
        val rerolled = record.copy(roll = record.roll.reroll(dice, which))
        return afterActionRoll(step.context, Applied(step.character, step.record.copy(roll = rerolled), step.context.request))
    }

    /** Burns momentum (or not) on a roll that offered it, then applies the outcome. */
    fun decideBurn(step: MoveStep.AwaitingBurn, burn: Boolean): MoveStep {
        val record = step.record.roll as RollRecord.Action
        var state = Applied(step.character, step.record, step.context.request)
        if (burn) {
            val burned = record.roll.burn(step.character.momentum)
            state = state.copy(
                character = step.character.copy(momentum = burned.momentum),
                record = step.record.copy(roll = record.copy(roll = burned.roll)),
            )
        }
        return applyOutcome(checkNotNull(state.record.roll).outcome, step.context, state)
    }

    /** Resolves a pending choice with the ids of the options picked. */
    fun choose(step: MoveStep.AwaitingChoice, optionIds: List<String>): MoveStep {
        val choice = step.choice
        require(optionIds.size == choice.count) { "Choose exactly ${choice.count}, got ${optionIds.size}" }
        require(optionIds.toSet().size == optionIds.size) { "Each option can be chosen once: $optionIds" }
        val options = optionIds.map { id ->
            requireNotNull(choice.options.firstOrNull { it.id == id }) { "Unknown option $id for ${step.record.move}" }
        }
        var state = Applied(step.character, step.record.copy(chosen = step.record.chosen + optionIds), step.context.request)
        for (option in options) state = apply(option.effects, step.context.move, state)
        return MoveStep.Done(state.character, state.record)
    }

    private data class Applied(val character: Character, val record: MoveRecord, val request: MoveRequest) {
        fun follow(followUp: FollowUp) = copy(record = record.copy(followUps = record.followUps + followUp))
    }

    private fun rollAction(roll: MoveRoll.Action, move: MoveDefinition, state: Applied): Pair<Applied, RollRecord.Action> {
        val character = state.character
        val source = when {
            roll.pick == SourcePick.HIGHEST -> roll.sources.maxBy { character.value(it) }
            roll.pick == SourcePick.LOWEST -> roll.sources.minBy { character.value(it) }
            roll.sources.size == 1 -> roll.sources.single()
            else -> requireNotNull(state.request.source?.takeIf { it in roll.sources }) {
                "${move.id} rolls one of ${roll.sources}; pick a source"
            }
        }
        val (used, kept) = character.pendingBonuses.partition { it.move == null || it.move == move.id }
        val adds = roll.bonuses.filter { it.id in state.request.bonuses }.sumOf { it.amount } + used.sumOf { it.amount }
        val result = ActionRoll.roll(dice, character.value(source), adds, character.momentum.value)
        return state.copy(character = character.copy(pendingBonuses = kept)) to RollRecord.Action(source, result)
    }

    /** Offers burning momentum when it would help; otherwise applies the outcome. */
    private fun afterActionRoll(context: MoveStep.Context, state: Applied): MoveStep {
        val record = state.record.roll as RollRecord.Action
        val burn = record.roll.burnOutcome(state.character.momentum)
        return if (burn != null) {
            MoveStep.AwaitingBurn(state.character, state.record, burn, context)
        } else {
            applyOutcome(record.outcome, context, state)
        }
    }

    private fun applyOutcome(outcome: Outcome, context: MoveStep.Context, state: Applied): MoveStep {
        val outcomes = checkNotNull(context.move.outcomes)
        val definition = when (outcome) {
            Outcome.STRONG_HIT -> outcomes.strongHit
            Outcome.WEAK_HIT -> outcomes.weakHit
            Outcome.MISS -> outcomes.miss
        }
        val after = apply(definition.effects, context.move, state)
        return offerChoices(listOfNotNull(definition.choice), context.with(after.request), after)
    }

    private fun offerChoices(choices: List<Choice>, context: MoveStep.Context, state: Applied): MoveStep {
        val choice = choices.firstOrNull { it.whenZero == null || state.character.meters[it.whenZero].value == 0 }
            ?: return MoveStep.Done(state.character, state.record)
        return MoveStep.AwaitingChoice(state.character, state.record, choice, context.with(state.request))
    }

    private fun apply(effects: List<Effect>, move: MoveDefinition, start: Applied): Applied =
        effects.fold(start) { state, effect -> apply(effect, move, state) }

    private fun apply(effect: Effect, move: MoveDefinition, state: Applied): Applied {
        val character = state.character
        return when (effect) {
            is Effect.AdjustMomentum -> adjustMomentum(effect.amount, state)
            is Effect.AdjustMeter -> adjustMeter(effect.meter, effect.amount, state)
            is Effect.Suffer -> {
                val amount = requireNotNull(state.request.amount) { "${move.id} needs an amount" }
                val (meters, loss) = character.meters.lose(effect.meter, amount)
                var next = state.copy(character = character.copy(meters = meters))
                if (effect.meter == MeterType.SUPPLY) next = afterSupplyLoss(loss.triggersOutOfSupply, loss.overflow, next)
                if (loss.overflow > 0 && effect.meter != MeterType.SUPPLY) next = adjustMomentum(-loss.overflow, next)
                next
            }
            is Effect.MarkProgress -> updateTrack(move, state) { it.markProgress(effect.times) }
            is Effect.ClearProgress -> updateTrack(move, state) { it.clearProgress(effect.keepBoxes) }
            is Effect.RaiseRank -> updateTrack(move, state) { it.raiseRank() }
            is Effect.StartTrack -> {
                val type = checkNotNull(move.progressTrack)
                val newTrack = requireNotNull(state.request.newTrack) { "${move.id} needs the name and rank of the new track" }
                val (id, updated) = character.startTrack(type, newTrack.name, newTrack.rank)
                state.copy(character = updated, request = state.request.copy(track = id), record = state.record.copy(track = id))
            }
            is Effect.EndTrack -> {
                val id = requireTrack(character, state.request, move, checkNotNull(move.progressTrack))
                state.copy(character = character.copy(tracks = character.tracks - id))
            }
            is Effect.MarkExperience -> {
                val id = requireTrack(character, state.request, move, checkNotNull(move.progressTrack))
                val rank = checkNotNull(character.tracks.getValue(id).rank)
                state.copy(character = character.copy(experience = character.experience + maxOf(rank.experience + effect.rankOffset, 0)))
            }
            is Effect.SpendExperience -> {
                require(character.experience >= effect.amount) {
                    "Not enough experience: ${character.experience}, needs ${effect.amount}"
                }
                state.copy(character = character.copy(experience = character.experience - effect.amount))
            }
            is Effect.MarkDebility -> state.copy(character = character.withDebilities(character.debilities.mark(effect.debility)))
            is Effect.ClearDebility -> state.copy(character = character.withDebilities(character.debilities.clear(effect.debility)))
            is Effect.NextRollBonus ->
                state.copy(character = character.copy(pendingBonuses = character.pendingBonuses + PendingBonus(effect.amount, effect.move)))
            is Effect.TriggerMove -> {
                val amount = if (effect.amountByRank) {
                    val id = requireTrack(character, state.request, move, checkNotNull(move.progressTrack))
                    checkNotNull(character.tracks.getValue(id).rank).harm
                } else {
                    effect.amount
                }
                state.follow(FollowUp.MakeMove(effect.move, amount))
            }
            is Effect.RollTable -> state.follow(FollowUp.RollOnTable(effect.table))
            is Effect.AskOracle -> state.follow(FollowUp.AskOracle)
            is Effect.EndCharacter -> state.copy(character = character.copy(isEnded = true)).follow(FollowUp.CharacterEnded)
        }
    }

    private fun adjustMomentum(amount: Int, state: Applied): Applied {
        val momentum = state.character.momentum
        if (amount >= 0) return state.copy(character = state.character.copy(momentum = momentum.gain(amount)))
        val loss = momentum.lose(-amount)
        val next = state.copy(character = state.character.copy(momentum = loss.momentum))
        return if (loss.requiresSetback) next.follow(FollowUp.MakeMove(FACE_A_SETBACK, loss.overflow)) else next
    }

    private fun adjustMeter(meter: MeterType, amount: Int, state: Applied): Applied {
        val character = state.character
        if (amount >= 0) {
            // While unprepared, supply cannot be increased.
            if (meter == MeterType.SUPPLY && Debility.UNPREPARED in character.debilities) return state
            return state.copy(character = character.copy(meters = character.meters.gain(meter, amount)))
        }
        val (meters, loss) = character.meters.lose(meter, -amount)
        val next = state.copy(character = character.copy(meters = meters))
        return if (meter == MeterType.SUPPLY) afterSupplyLoss(loss.triggersOutOfSupply, loss.overflow, next) else next
    }

    /** Supply ran out: Out of Supply follows, carrying any -supply that did not fit below 0. */
    private fun afterSupplyLoss(outOfSupply: Boolean, overflow: Int, state: Applied): Applied =
        if (outOfSupply) state.follow(FollowUp.MakeMove(OUT_OF_SUPPLY, overflow.takeIf { it > 0 })) else state

    private fun updateTrack(move: MoveDefinition, state: Applied, change: (ProgressTrack) -> ProgressTrack): Applied {
        val id = requireTrack(state.character, state.request, move, checkNotNull(move.progressTrack))
        val tracks = state.character.tracks
        return state.copy(character = state.character.copy(tracks = tracks + (id to change(tracks.getValue(id)))))
    }

    private fun requireTrack(character: Character, request: MoveRequest, move: MoveDefinition, type: ProgressTrackType): String {
        val id = requireNotNull(request.track) { "${move.id} needs a ${type.name.lowercase()} track" }
        val track = requireNotNull(character.tracks[id]) { "No track $id" }
        require(track.type == type) { "${move.id} needs a ${type.name.lowercase()} track, $id is a ${track.type.name.lowercase()}" }
        return id
    }

    private companion object {
        const val FACE_A_SETBACK = "face_a_setback"
        const val OUT_OF_SUPPLY = "out_of_supply"
    }
}
