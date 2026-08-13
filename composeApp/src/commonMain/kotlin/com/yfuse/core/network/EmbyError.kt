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
) : Exception(error.toString())
