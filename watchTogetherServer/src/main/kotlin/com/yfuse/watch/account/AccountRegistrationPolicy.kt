package com.yfuse.watch.account

data class AccountRegistrationPolicy(
    val enabled: Boolean = false,
    val maxUsers: Int = 1_000,
    /** One-time codes. Only SHA-256 digests are persisted by the account store. */
    val invitationCodes: Set<String> = emptySet(),
    /** Usernames are resolved to immutable user ids when the store starts or creates a user. */
    val inviteIssuerUsernames: Set<String> = emptySet(),
    val issuedInviteTtlMs: Long = DEFAULT_ISSUED_INVITE_TTL_MS,
) {
    init {
        require(maxUsers in 1..MAX_ALLOWED_USERS)
        require(invitationCodes.all { INVITE_PATTERN.matches(it) })
        require(inviteIssuerUsernames.all { USERNAME_PATTERN.matches(it) && it == it.lowercase() })
        require(issuedInviteTtlMs in 1..MAX_ISSUED_INVITE_TTL_MS)
    }

    companion object {
        private const val MAX_ALLOWED_USERS = 100_000
        private val INVITE_PATTERN = Regex("[A-Za-z0-9_-]{12,128}")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AccountRegistrationPolicy {
            val enabled =
                when (val raw = environment["ACCOUNT_REGISTRATION_ENABLED"]?.trim()?.lowercase()) {
                    // Production-safe default: operators must deliberately open registration for
                    // initial provisioning instead of accidentally exposing an unlimited signup API.
                    null, "" -> false
                    "true" -> true
                    "false" -> false
                    else -> error("ACCOUNT_REGISTRATION_ENABLED must be true or false")
                }
            val rawMaxUsers = environment["ACCOUNT_MAX_USERS"]?.trim()
            val maxUsers =
                if (rawMaxUsers.isNullOrEmpty()) {
                    DEFAULT_MAX_USERS
                } else {
                    rawMaxUsers.toIntOrNull()
                        ?: error("ACCOUNT_MAX_USERS must be an integer")
                }
            val invitationCodes =
                environment["ACCOUNT_REGISTRATION_INVITE_CODES"]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.toSet()
                    .orEmpty()
            val inviteIssuerUsernames =
                environment["ACCOUNT_INVITE_ISSUER_USERNAMES"]
                    ?.split(',')
                    ?.map { it.trim().lowercase() }
                    ?.filter(String::isNotEmpty)
                    ?.toSet()
                    .orEmpty()
            val rawIssuedInviteTtlHours =
                environment["ACCOUNT_ISSUED_INVITE_TTL_HOURS"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            val issuedInviteTtlHours =
                rawIssuedInviteTtlHours
                    ?.toLongOrNull()
                    ?: if (rawIssuedInviteTtlHours == null) {
                        DEFAULT_ISSUED_INVITE_TTL_HOURS
                    } else {
                        error("ACCOUNT_ISSUED_INVITE_TTL_HOURS must be an integer")
                    }
            if (issuedInviteTtlHours !in 1..MAX_ISSUED_INVITE_TTL_HOURS) {
                error("ACCOUNT_ISSUED_INVITE_TTL_HOURS must be between 1 and 168")
            }
            return AccountRegistrationPolicy(
                enabled = enabled,
                maxUsers = maxUsers,
                invitationCodes = invitationCodes,
                inviteIssuerUsernames = inviteIssuerUsernames,
                issuedInviteTtlMs = Math.multiplyExact(issuedInviteTtlHours, 60L * 60_000L),
            )
        }

        private const val DEFAULT_MAX_USERS = 1_000
        private const val DEFAULT_ISSUED_INVITE_TTL_HOURS = 24L
        private const val MAX_ISSUED_INVITE_TTL_HOURS = 168L
        private const val DEFAULT_ISSUED_INVITE_TTL_MS =
            DEFAULT_ISSUED_INVITE_TTL_HOURS * 60L * 60_000L
        private const val MAX_ISSUED_INVITE_TTL_MS = 7L * 24 * 60 * 60_000L
        private val USERNAME_PATTERN = Regex("[a-z0-9][a-z0-9_.-]{2,39}")
    }
}
