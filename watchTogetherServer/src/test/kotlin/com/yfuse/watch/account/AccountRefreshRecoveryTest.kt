package com.yfuse.watch.account

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AccountRefreshRecoveryTest {
    @Test
    fun lost_response_can_be_recovered_after_service_restart_without_extending_expiry() {
        Fixture().use { fixture ->
            val request = fixture.request()
            val first = fixture.service().refresh(request)
            fixture.now += 16 * 60_000L
            val recovered = fixture.service().refresh(request)
            assertEquals(first, recovered)
            // The recovered access token may be expired; its refresh token still permits rotation.
            val next = fixture.service().refresh(RefreshRequest(recovered.refreshToken, requestId = requestId()))
            assertNotEquals(recovered.refreshToken, next.refreshToken)
        }
    }

    @Test
    fun concurrent_retries_return_one_successor() {
        Fixture().use { fixture ->
            val request = fixture.request()
            val service = fixture.service()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<AuthResponse> { service.refresh(request) }
                val second = executor.submit<AuthResponse> { service.refresh(request) }
                assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun a_different_request_id_cannot_replay_a_spent_token() {
        Fixture().use { fixture ->
            val request = fixture.request()
            fixture.service().refresh(request)
            assertUnauthorized { fixture.service().refresh(request.copy(requestId = requestId())) }
            assertUnauthorized { fixture.service().refresh(request.copy(requestId = null)) }
        }
    }

    @Test
    fun recovery_cannot_revive_a_revoked_session() {
        Fixture().use { fixture ->
            val request = fixture.request()
            val refreshed = fixture.service().refresh(request)
            fixture.service().logout(refreshed.accessToken)
            assertUnauthorized { fixture.service().refresh(request) }
        }
    }

    @Test
    fun recovery_cannot_revive_a_subsequently_rotated_session() {
        Fixture().use { fixture ->
            val request = fixture.request()
            val refreshed = fixture.service().refresh(request)
            fixture.service().refresh(RefreshRequest(refreshed.refreshToken, requestId = requestId()))
            assertUnauthorized { fixture.service().refresh(request) }
        }
    }

    @Test
    fun recovery_cannot_extend_an_expired_refresh_token() {
        Fixture().use { fixture ->
            val request = fixture.request()
            val refreshed = fixture.service().refresh(request)
            fixture.now = refreshed.refreshExpiresAtEpochMs
            assertUnauthorized { fixture.service().refresh(request) }
        }
    }

    @Test
    fun legacy_refresh_remains_single_use() {
        Fixture().use { fixture ->
            val request = fixture.request().copy(requestId = null)
            fixture.service().refresh(request)
            assertUnauthorized { fixture.service().refresh(request) }
        }
    }

    private class Fixture : AutoCloseable {
        val store = SqliteAccountStore.inMemory()
        var now = 1_700_000_000_000L

        fun service() =
            AccountService(
                store = store,
                passwordHasher = Pbkdf2PasswordHasher(iterations = 1_000),
                clock = { now },
                usernameFailureLimiter = UsernameFailureLimiter(),
                syncUserRateLimiter = AccountRateLimiter(),
                registrationPolicy = AccountRegistrationPolicy(enabled = true),
            )

        fun request(): RefreshRequest {
            val auth = service().register(RegisterRequest("Alice", "long-password-123"))
            return RefreshRequest(auth.refreshToken, requestId = requestId())
        }

        override fun close() = store.close()
    }

    private companion object {
        fun requestId(): String = SessionTokenFactory().issue().plaintext

        fun assertUnauthorized(block: () -> Unit) {
            assertEquals(AccountProblem.Unauthorized, assertFailsWith<AccountServiceException>(block = block).problem)
        }
    }
}
