package com.yfuse.core.network

/** Domain-level error categories mapped from transport/HTTP failures. */
sealed interface EmbyError {
    data object Network : EmbyError

    data object Unauthorized : EmbyError

    /**
     * The request reached an intermediary or server, but an access policy rejected it.
     *
     * This is deliberately separate from [Unauthorized]: Cloudflare/WAF 403 pages are not
     * repaired by asking the user to sign in again.
     */
    data class AccessDenied(
        val provider: String? = null,
    ) : EmbyError

    /**
     * The request reached the server, which answered that the addressed item does not exist.
     *
     * Deliberately separate from [Unknown]: a missing item is a settled answer, so callers must be
     * able to drop the work instead of retrying a request that can never start succeeding.
     */
    data object NotFound : EmbyError

    data class Server(
        val code: Int,
    ) : EmbyError

    data class Unknown(
        val message: String,
    ) : EmbyError
}

/** Carries an [EmbyError] through [Result.failure]. */
class EmbyErrorException(
    val error: EmbyError,
    /**
     * The client answered from an active cooldown without contacting the server. The failure
     * repeats one the server already gave, so it is neither a new malfunction to log at error
     * level nor another strike against the server's health.
     */
    val fromCooldown: Boolean = false,
) : Exception(error.toString())
