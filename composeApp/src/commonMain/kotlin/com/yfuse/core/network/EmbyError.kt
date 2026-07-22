package com.yfuse.core.network

/** Domain-level error categories mapped from transport/HTTP failures. */
sealed interface EmbyError {
    data object Network : EmbyError
    data object Unauthorized : EmbyError
    data class Server(val code: Int) : EmbyError
    data class Unknown(val message: String) : EmbyError
}

/** Carries an [EmbyError] through [Result.failure]. */
class EmbyErrorException(val error: EmbyError) : Exception(error.toString())
