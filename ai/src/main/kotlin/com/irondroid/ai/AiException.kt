package com.irondroid.ai

/** The kinds of failure every provider reports the same way, so the app can explain them to the player. */
enum class AiErrorKind(
    /** Trying the same request again later may work. */
    val retryable: Boolean,
) {
    /** The API key is missing, wrong or revoked. */
    AUTHENTICATION(retryable = false),

    /** The account has no balance or credit left. */
    INSUFFICIENT_BALANCE(retryable = false),

    /** Too many requests: wait and try again. */
    RATE_LIMITED(retryable = true),

    /** The provider does not offer the requested model. */
    MODEL_NOT_FOUND(retryable = false),

    /** The provider rejected the request itself (too long, bad parameters). */
    INVALID_REQUEST(retryable = false),

    /** No connection, or the connection dropped or timed out. */
    NETWORK(retryable = true),

    /** The provider failed or is overloaded. */
    SERVER(retryable = true),

    /** The provider answered something this app cannot read. */
    MALFORMED_RESPONSE(retryable = true),
}

/**
 * A failure talking to an AI provider. [retryAfterSeconds] is the wait the provider asked for, when
 * it said one.
 */
class AiException(
    val kind: AiErrorKind,
    message: String,
    cause: Throwable? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(message, cause) {
    val retryable: Boolean get() = kind.retryable
}
