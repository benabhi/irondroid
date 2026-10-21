package com.irondroid.ai.narrator

/** A move the narrator wants to roll, shown to the player before the dice (RF-ENV-04). */
data class MoveProposal(
    /** Move id, such as `face_danger`. */
    val move: String,
    /** Stat or meter the narrator picked, or null when the move picks it itself. */
    val source: String?,
)

/** What the player answered to a [MoveProposal]. */
sealed interface MoveDecision {
    /** Roll [move] with [source]: the proposal as it was, or changed by the player. */
    data class Roll(val move: String, val source: String?) : MoveDecision

    /** Do not roll: the story goes on without dice. */
    data object Skip : MoveDecision
}

/** Asks the player before the narrator rolls a move (IRON-53). */
interface MoveConfirmer {
    /** Whether rolling [move] waits for the player; false for moves without dice or with confirmation off. */
    fun needsConfirmation(move: String): Boolean

    /** Waits for the player's answer to [proposal]. */
    suspend fun confirm(proposal: MoveProposal): MoveDecision
}
