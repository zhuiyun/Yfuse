package com.yfuse.watch.account

data class AccountRegistrationPolicy(
    val enabled: Boolean = false,
    val maxUsers: Int = 1_000,
    /** One-time codes. Only SHA-256 digests are persisted by the account store. */
    val invitationCodes: Set<String> = emptySet(),
) {
    init {
        require(maxUsers in 1..MAX_ALLOWED_USERS)
        require(invitationCodes.all { INVITE_PATTERN.matches(it) })
    }

    companion object {
        private const val MAX_ALLOWED_USERS = 100_000
        private val INVITE_PATTERN = Regex("[A-Za-z0-9_-]{12,128}")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AccountRegistrationPolicy {
            val enabled = when (val raw = environment["ACCOUNT_REGISTRATION_ENABLED"]?.trim()?.lowercase()) {
                // Production-safe default: operators must deliberately open registration for
                // initial provisioning instead of accidentally exposing an unlimited signup API.
                null, "" -> false
                "true" -> true
                "false" -> false
                else -> error("ACCOUNT_REGISTRATION_ENABLED must be true or false")
            }
            val rawMaxUsers = environment["ACCOUNT_MAX_USERS"]?.trim()
            val maxUsers = if (rawMaxUsers.isNullOrEmpty()) {
                DEFAULT_MAX_USERS
            } else {
                rawMaxUsers.toIntOrNull()
                    ?: error("ACCOUNT_MAX_USERS must be an integer")
            }
            val invitationCodes = environment["ACCOUNT_REGISTRATION_INVITE_CODES"]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()
            return AccountRegistrationPolicy(
                enabled = enabled || invitationCodes.isNotEmpty(),
                maxUsers = maxUsers,
                invitationCodes = invitationCodes,
            )
        }

        private const val DEFAULT_MAX_USERS = 1_000
    }
}
