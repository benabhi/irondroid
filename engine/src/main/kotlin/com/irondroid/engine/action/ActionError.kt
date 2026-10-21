package com.irondroid.engine.action

/** Why an action did not run. The screens show their own localized text for each code. */
enum class ActionErrorCode {
    /** No action has that name. */
    UNKNOWN_ACTION,

    /** No read-only resource has that URI. */
    UNKNOWN_RESOURCE,

    /** The arguments do not fit the action's parameters. */
    INVALID_ARGUMENTS,

    /** The rules do not allow it, such as Strike without initiative. */
    RULE_VIOLATION,

    /** The game is not in a state that allows it, such as marking progress on a track that does not exist. */
    INVALID_STATE,

    /** Something failed inside the app; the call can be retried or reported. */
    INTERNAL,
}

/**
 * An explained error (RF-API-05), written for the AI. [details] lists each problem found, such as
 * every wrong argument; [suggestion] says what to do instead, such as "use Clash, not Strike".
 */
data class ActionError(
    val code: ActionErrorCode,
    val message: String,
    val details: List<String> = emptyList(),
    val suggestion: String? = null,
)

/**
 * Thrown by an [ActionHandler] to refuse a call that the rules or the game state do not allow.
 * The dispatcher turns it into a failed result; the handler must not have changed anything yet.
 */
class ActionRejectedException(val error: ActionError) : RuntimeException(error.message) {
    companion object {
        fun rule(message: String, suggestion: String? = null) =
            ActionRejectedException(ActionError(ActionErrorCode.RULE_VIOLATION, message, suggestion = suggestion))

        fun state(message: String, suggestion: String? = null) =
            ActionRejectedException(ActionError(ActionErrorCode.INVALID_STATE, message, suggestion = suggestion))
    }
}
