package com.yfuse.core.network

/** Domain-level error categories mapped from transport/HTTP failures. */
sealed interface EmbyError {
    /**
     * No HTTP answer came back.
     *
     * The subtypes only record what the transport reported, so a message can point at the
     * address, the port or the certificate. Retry, failover and health policy treat them all as
     * the same outage: match on this interface there, not on [Network] alone.
     */
    sealed interface Unreachable : EmbyError

    /** A transport failure the other [Unreachable] subtypes do not name, or a server cooling down. */
    data object Network : Unreachable

    /**
     * The TLS handshake failed: an untrusted, self-signed or mismatched certificate, or HTTPS
     * spoken to a port that only serves HTTP.
     */
    data object Certificate : Unreachable

    /** The host name did not resolve: a mistyped address, or a device with no network at all. */
    data object HostNotFound : Unreachable

    /** Connecting, or waiting for the answer, took longer than the client allows. */
    data object Timeout : Unreachable

    /** The host refused the connection: nothing listens on that port. */
    data object ConnectionRefused : Unreachable

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

    /**
     * A sign-in reached something that is not an Emby or Jellyfin server: a web page, another
     * service on that port, or the right host under the wrong path.
     */
    data object NotMediaServer : EmbyError

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
