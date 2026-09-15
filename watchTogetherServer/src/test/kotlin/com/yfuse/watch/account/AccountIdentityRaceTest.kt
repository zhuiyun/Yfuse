package com.yfuse.watch.account

import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountIdentityRaceTest {
    @Test
    fun password_change_rejects_an_old_password_login_waiting_to_create_its_session() {
        IdentityRaceFixture().use { fixture ->
            val original = fixture.register()
            fixture.store.blockNext(IdentityMutation.Login)
            val pendingLogin = fixture.submit { fixture.login() }
            fixture.store.awaitBlocked()

            val replacement = fixture.service.changePassword(original.accessToken, passwordChange())
            fixture.store.releaseBlocked()

            assertAccountFailure(pendingLogin.get(5, TimeUnit.SECONDS), AccountProblem.InvalidCredentials)
            val sessions = fixture.service.listSessions(replacement.accessToken).sessions
            assertEquals(1, sessions.size)
            assertTrue(sessions.single().current)
            assertEquals(original.user.id, fixture.login(NEW_PASSWORD).user.id)
            assertAccountFailure(runCatching { fixture.login() }, AccountProblem.InvalidCredentials)
        }
    }

    @Test
    fun password_change_revokes_a_login_that_committed_before_the_change() {
        IdentityRaceFixture().use { fixture ->
            val original = fixture.register()
            val login = fixture.login()

            val replacement = fixture.service.changePassword(original.accessToken, passwordChange())

            assertAccountFailure(
                runCatching { fixture.service.getProfile(login.accessToken) },
                AccountProblem.Unauthorized,
            )
            assertAccountFailure(
                runCatching { fixture.service.refresh(RefreshRequest(login.refreshToken)) },
                AccountProblem.Unauthorized,
            )
            assertEquals(
                1,
                fixture.service
                    .listSessions(replacement.accessToken)
                    .sessions.size,
            )
        }
    }

    @Test
    fun deleted_account_rejects_a_previously_verified_login_without_recreating_a_session() {
        IdentityRaceFixture().use { fixture ->
            val original = fixture.register()
            fixture.store.blockNext(IdentityMutation.Login)
            val pendingLogin = fixture.submit { fixture.login() }
            fixture.store.awaitBlocked()

            fixture.service.deleteAccount(original.accessToken, DeleteAccountRequest(CURRENT_PASSWORD))
            fixture.store.releaseBlocked()

            assertAccountFailure(pendingLogin.get(5, TimeUnit.SECONDS), AccountProblem.InvalidCredentials)
        }
    }

    @Test
    fun profile_changes_to_different_fields_survive_either_write_order() {
        val nickname = UpdateProfileRequest(nickname = "Updated nickname")
        val avatar = UpdateProfileRequest(avatarId = 5)
        listOf(nickname to avatar, avatar to nickname).forEach { (pending, immediate) ->
            IdentityRaceFixture().use { fixture ->
                val session = fixture.register()
                fixture.store.blockNext(IdentityMutation.Profile)
                val pendingUpdate =
                    fixture.submit { fixture.service.updateProfile(session.accessToken, pending) }
                fixture.store.awaitBlocked()

                fixture.service.updateProfile(session.accessToken, immediate)
                fixture.store.releaseBlocked()
                val response = pendingUpdate.get(5, TimeUnit.SECONDS).getOrThrow()

                assertEquals("Updated nickname", response.nickname)
                assertEquals(5, response.avatarId)
                assertEquals(response, fixture.service.getProfile(session.accessToken))
            }
        }
    }

    @Test
    fun concurrent_profile_change_does_not_invalidate_login_and_returns_current_profile() {
        IdentityRaceFixture().use { fixture ->
            val original = fixture.register()
            fixture.store.blockNext(IdentityMutation.Login)
            val pendingLogin = fixture.submit { fixture.login() }
            fixture.store.awaitBlocked()

            val profile =
                fixture.service.updateProfile(
                    original.accessToken,
                    UpdateProfileRequest(nickname = "Updated nickname", avatarId = 5),
                )
            fixture.store.releaseBlocked()

            assertEquals(profile, pendingLogin.get(5, TimeUnit.SECONDS).getOrThrow().user)
        }
    }
}

private class IdentityRaceFixture : AutoCloseable {
    val store = BlockingIdentityStore(SqliteAccountStore.inMemory())
    val service =
        AccountService(
            store = store,
            passwordHasher = Pbkdf2PasswordHasher(iterations = 1_000),
            usernameFailureLimiter = UsernameFailureLimiter(),
            syncUserRateLimiter = AccountRateLimiter(),
            registrationPolicy = AccountRegistrationPolicy(enabled = true),
        )
    private val executor = Executors.newSingleThreadExecutor()

    fun register(): AuthResponse =
        service.register(
            RegisterRequest(
                username = "Alice",
                password = CURRENT_PASSWORD,
                nickname = "Alice",
                avatarId = 1,
            ),
        )

    fun login(password: String = CURRENT_PASSWORD): AuthResponse = service.login(LoginRequest("Alice", password))

    fun <T> submit(block: () -> T): CompletableFuture<Result<T>> =
        CompletableFuture.supplyAsync({ runCatching(block) }, executor)

    override fun close() {
        store.releaseBlocked()
        executor.shutdownNow()
        try {
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            store.close()
        }
    }
}

private enum class IdentityMutation {
    Login,
    Profile,
}

private class BlockingIdentityStore(
    private val delegate: AccountStore,
) : AccountStore by delegate {
    private val nextBlockedMutation = AtomicReference<IdentityMutation?>()
    private val mutationBlocked = CountDownLatch(1)
    private val releaseMutation = CountDownLatch(1)

    fun blockNext(mutation: IdentityMutation) {
        check(nextBlockedMutation.compareAndSet(null, mutation))
    }

    fun awaitBlocked() {
        check(mutationBlocked.await(5, TimeUnit.SECONDS)) { "identity mutation did not reach store" }
    }

    fun releaseBlocked() {
        releaseMutation.countDown()
    }

    override fun createSessionIfCredentialsMatch(
        session: NewSession,
        expectedCurrent: PasswordDigest,
    ): StoredUser? {
        blockIfArmed(IdentityMutation.Login)
        return delegate.createSessionIfCredentialsMatch(session, expectedCurrent)
    }

    override fun updateProfile(
        userId: String,
        nickname: String?,
        avatarId: Int?,
        updatedAtEpochMs: Long,
    ): StoredUser? {
        blockIfArmed(IdentityMutation.Profile)
        return delegate.updateProfile(userId, nickname, avatarId, updatedAtEpochMs)
    }

    private fun blockIfArmed(mutation: IdentityMutation) {
        if (!nextBlockedMutation.compareAndSet(mutation, null)) return
        mutationBlocked.countDown()
        check(releaseMutation.await(5, TimeUnit.SECONDS)) { "identity mutation was not released" }
    }
}

private fun passwordChange(): ChangePasswordRequest =
    ChangePasswordRequest(
        currentPassword = CURRENT_PASSWORD,
        newPassword = NEW_PASSWORD,
        expectedSyncVersion = 0L,
        keyVersion = 1,
        wrapVersion = 1,
        wrapKdf = "PBKDF2-HMAC-SHA256",
        wrapIterations = 600_000,
        wrappedVaultKey = encodedIdentityBytes(48, 42),
        wrapSalt = encodedIdentityBytes(16, 43),
        wrapNonce = encodedIdentityBytes(12, 44),
    )

private fun encodedIdentityBytes(
    size: Int,
    value: Byte,
): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size) { value })

private fun assertAccountFailure(
    result: Result<*>,
    problem: AccountProblem,
) {
    assertEquals(problem, assertIs<AccountServiceException>(result.exceptionOrNull()).problem)
}

private const val CURRENT_PASSWORD = "Correct-Horse-42"
private const val NEW_PASSWORD = "New-Correct-Horse-43"
