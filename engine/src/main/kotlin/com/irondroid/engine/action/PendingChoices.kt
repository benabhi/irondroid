package com.irondroid.engine.action

/**
 * The choice the player has to make now, if any (RF-API-07). Moves stop at one choice at a time,
 * so at most one is open. The dispatcher opens choices that results bring; the handlers of
 * `suggest_option` and `choose_option` call [suggest] and [decide].
 *
 * The open choice lives in memory only: closing the app drops it.
 */
class PendingChoices {
    private var counter = 0

    /** The open choice, or null. */
    var current: PendingChoice? = null
        private set

    /** Opens [choice] with a new id. */
    internal fun open(choice: PendingChoice) {
        check(current == null) { "Choice ${current?.id} is still open" }
        choice.id = "choice_${++counter}"
        current = choice
    }

    /** Marks what the AI (or anyone helping the player) suggests, replacing an earlier suggestion. */
    fun suggest(choiceId: String, optionIds: List<String>, why: String, proposal: String? = null) {
        val choice = find(choiceId)
        checkOptions(choice, optionIds, exact = false)
        if (why.isBlank()) throw ActionRejectedException.rule("Say why you suggest it")
        choice.suggestion = Suggestion(optionIds, why, proposal?.trim()?.ifBlank { null })
    }

    /**
     * The player picks [optionIds]: the choice closes and its action goes on. The result may bring
     * the next choice, which the dispatcher opens. Only the player decides.
     */
    fun decide(choiceId: String, optionIds: List<String>, caller: Caller): ActionResult {
        if (caller != Caller.PLAYER) {
            throw ActionRejectedException.rule(
                "Only the player decides a choice",
                "Mark your suggestion with suggest_option and let the player pick",
            )
        }
        val choice = find(choiceId)
        checkOptions(choice, optionIds, exact = true)
        current = null
        return try {
            choice.resume(optionIds)
        } catch (e: Exception) {
            current = choice // Nothing went on: the player can decide again.
            throw e
        }
    }

    /** Drops the open choice without going on, as when its action is undone. */
    fun cancel() {
        current = null
    }

    private fun find(choiceId: String): PendingChoice {
        val choice = current ?: throw ActionRejectedException.state("There is no choice to make")
        if (choice.id != choiceId) {
            throw ActionRejectedException.state("Choice $choiceId is not open", "The open choice is ${choice.id}")
        }
        return choice
    }

    private fun checkOptions(choice: PendingChoice, optionIds: List<String>, exact: Boolean) {
        val known = choice.options.map { it.id }
        val unknown = optionIds.filterNot { it in known }
        if (unknown.isNotEmpty()) {
            throw ActionRejectedException.rule(
                "Choice ${choice.id} has no option ${unknown.joinToString(", ")}",
                "Pick among ${known.joinToString(", ")}",
            )
        }
        if (optionIds.toSet().size != optionIds.size) throw ActionRejectedException.rule("An option is picked twice")
        val fits = if (exact) optionIds.size == choice.count else optionIds.size in 1..choice.count
        if (!fits) {
            val expected = if (exact) "exactly ${choice.count}" else "1 to ${choice.count}"
            throw ActionRejectedException.rule("Choice ${choice.id} takes $expected options, got ${optionIds.size}")
        }
    }
}
