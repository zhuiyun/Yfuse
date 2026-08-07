package com.yfuse.watch.account

data class AccountRegistrationPolicy(
    val enabled: Boolean = true,
    val maxUsers: Int = 1_000,
) {
    init {
        require(maxUsers in 1..MAX_ALLOWED_USERS)
    }

    companion object {
        private const val MAX_ALLOWED_USERS = 100_000

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AccountRegistrationPolicy {
            val enabled = when (val raw = environment["ACCOUNT_REGISTRATION_ENABLED"]?.trim()?.lowercase()) {
                null, "" -> true
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
            return AccountRegistrationPolicy(enabled = enabled, maxUsers = maxUsers)
        }

        private const val DEFAULT_MAX_USERS = 1_000
    }
}
