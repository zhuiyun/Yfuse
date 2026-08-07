package com.yfuse.watch.account

import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountSyncSessionRaceTest {
    @Test
    fun password_change_committed_before_put_prevents_old_wrapper_rollback() {
        RaceFixture().use { fixture ->
            val uploadSession = fixture.register()
            val passwordSession = fixture.login()
            val original = fixture.service.putSync(
                uploadSession.accessToken,
                putRequest(baseVersion = 0L, nonceByte = 1, wrapperByte = 11),
            )
            assertEquals(1L, original.version)

            fixture.store.blockNext(BlockedMutation.Put)
            val pendingPut = fixture.submitMutation {
                fixture.service.putSync(
                    uploadSession.accessToken,
                    putRequest(baseVersion = 1L, nonceByte = 2, wrapperByte = 11),
                )
            }
            fixture.store.awaitBlocked()

            val replacement = fixture.service.changePassword(
                passwordSession.accessToken,
                passwordRequest(expectedSyncVersion = 1L, wrapperByte = 42),
            )
            fixture.store.releaseBlocked()

            assertUnauthorized(pendingPut.get(5, TimeUnit.SECONDS))
            val persisted = fixture.service.getSync(replacement.accessToken)
            assertEquals(1L, persisted.version)
            assertEquals(original.payload?.ciphertext, persisted.payload?.ciphertext)
            assertEquals(encodedBytes(48, 42), persisted.payload?.wrappedVaultKey)
        }
    }

    @Test
    fun password_change_committed_before_delete_keeps_never_written_state_empty() {
        RaceFixture().use { fixture ->
            val deleteSession = fixture.register()
            val passwordSession = fixture.login()

            fixture.store.blockNext(BlockedMutation.Delete)
            val pendingDelete = fixture.submitMutation {
                fixture.service.deleteSync(deleteSession.accessToken)
            }
            fixture.store.awaitBlocked()

            val replacement = fixture.service.changePassword(
                passwordSession.accessToken,
                passwordRequest(expectedSyncVersion = 0L, wrapperByte = 42),
            )
            fixture.store.releaseBlocked()

            assertUnauthorized(pendingDelete.get(5, TimeUnit.SECONDS))
            val persisted = fixture.service.getSync(replacement.accessToken)
            assertEquals(0L, persisted.version)
            assertNull(persisted.payload)
            assertNull(persisted.updatedAtEpochMs)
        }
    }

    @Test
    fun password_change_committed_before_put_keeps_tombstone_unchanged() {
        RaceFixture().use { fixture ->
            val uploadSession = fixture.register()
            val passwordSession = fixture.login()
            fixture.service.putSync(
                uploadSession.accessToken,
                putRequest(baseVersion = 0L, nonceByte = 1, wrapperByte = 11),
            )
            val tombstone = fixture.service.deleteSync(uploadSession.accessToken)
            assertEquals(2L, tombstone.version)
            assertNull(tombstone.payload)

            fixture.store.blockNext(BlockedMutation.Put)
            val pendingPut = fixture.submitMutation {
                fixture.service.putSync(
                    uploadSession.accessToken,
                    putRequest(baseVersion = 2L, nonceByte = 2, wrapperByte = 11),
                )
            }
            fixture.store.awaitBlocked()

            val replacement = fixture.service.changePassword(
                passwordSession.accessToken,
                passwordRequest(expectedSyncVersion = 2L, wrapperByte = 42),
            )
            fixture.store.releaseBlocked()

            assertUnauthorized(pendingPut.get(5, TimeUnit.SECONDS))
            val persisted = fixture.service.getSync(replacement.accessToken)
            assertEquals(2L, persisted.version)
            assertNull(persisted.payload)
            assertEquals(tombstone.updatedAtEpochMs, persisted.updatedAtEpochMs)
        }
    }
}

private class RaceFixture : AutoCloseable {
    private val backingStore = SqliteAccountStore.inMemory()
    val store = BlockingMutationStore(backingStore)
    val service = AccountService(
        store = store,
        passwordHasher = Pbkdf2PasswordHasher(iterations = 1_000),
        usernameFailureLimiter = UsernameFailureLimiter(),
        syncUserRateLimiter = AccountRateLimiter(),
        registrationPolicy = AccountRegistrationPolicy(),
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun register(): AuthResponse = service.register(
        RegisterRequest(
            username = "Alice",
            password = CURRENT_PASSWORD,
            nickname = "Alice",
            avatarId = 1,
        ),
    )

    fun login(): AuthResponse = service.login(LoginRequest("Alice", CURRENT_PASSWORD))

    fun submitMutation(block: () -> Unit): CompletableFuture<AccountServiceException?> =
        CompletableFuture.supplyAsync(
            {
                try {
                    block()
                    null
                } catch (failure: AccountServiceException) {
                    failure
                }
            },
            executor,
        )

    override fun close() {
        store.releaseBlocked()
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        store.close()
    }
}

private enum class BlockedMutation {
    Put,
    Delete,
}

private class BlockingMutationStore(
    private val delegate: AccountStore,
) : AccountStore by delegate {
    private val nextBlockedMutation = AtomicReference<BlockedMutation?>()
    private val mutationBlocked = CountDownLatch(1)
    private val releaseMutation = CountDownLatch(1)

    fun blockNext(mutation: BlockedMutation) {
        check(nextBlockedMutation.compareAndSet(null, mutation))
    }

    fun awaitBlocked() {
        check(mutationBlocked.await(5, TimeUnit.SECONDS)) { "sync mutation did not reach store" }
    }

    fun releaseBlocked() {
        releaseMutation.countDown()
    }

    override fun putSyncRecord(
        record: StoredSyncRecord,
        baseVersion: Long,
        authenticatedSessionId: String,
        nowEpochMs: Long,
    ): SyncWriteResult {
        blockIfArmed(BlockedMutation.Put)
        return delegate.putSyncRecord(
            record = record,
            baseVersion = baseVersion,
            authenticatedSessionId = authenticatedSessionId,
            nowEpochMs = nowEpochMs,
        )
    }

    override fun deleteSyncData(
        userId: String,
        authenticatedSessionId: String,
        updatedAtEpochMs: Long,
    ): SyncDeleteResult {
        blockIfArmed(BlockedMutation.Delete)
        return delegate.deleteSyncData(
            userId = userId,
            authenticatedSessionId = authenticatedSessionId,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    private fun blockIfArmed(mutation: BlockedMutation) {
        if (!nextBlockedMutation.compareAndSet(mutation, null)) return
        mutationBlocked.countDown()
        check(releaseMutation.await(5, TimeUnit.SECONDS)) { "sync mutation was not released" }
    }
}

private fun putRequest(
    baseVersion: Long,
    nonceByte: Byte,
    wrapperByte: Byte,
): PutSyncRequest = PutSyncRequest(
    baseVersion = baseVersion,
    payload = EncryptedSyncEnvelope(
        schemaVersion = 1,
        algorithm = "AES-256-GCM",
        keyVersion = 1,
        nonce = encodedBytes(12, nonceByte),
        ciphertext = encodedBytes(32, 9),
        wrapVersion = 1,
        wrapKdf = "PBKDF2-HMAC-SHA256",
        wrapIterations = 600_000,
        wrappedVaultKey = encodedBytes(48, wrapperByte),
        wrapSalt = encodedBytes(16, (wrapperByte + 1).toByte()),
        wrapNonce = encodedBytes(12, (wrapperByte + 2).toByte()),
    ),
)

private fun passwordRequest(
    expectedSyncVersion: Long,
    wrapperByte: Byte,
): ChangePasswordRequest = ChangePasswordRequest(
    currentPassword = CURRENT_PASSWORD,
    newPassword = NEW_PASSWORD,
    expectedSyncVersion = expectedSyncVersion,
    keyVersion = 1,
    wrapVersion = 1,
    wrapKdf = "PBKDF2-HMAC-SHA256",
    wrapIterations = 600_000,
    wrappedVaultKey = encodedBytes(48, wrapperByte),
    wrapSalt = encodedBytes(16, (wrapperByte + 1).toByte()),
    wrapNonce = encodedBytes(12, (wrapperByte + 2).toByte()),
)

private fun encodedBytes(size: Int, value: Byte): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size) { value })

private fun assertUnauthorized(failure: AccountServiceException?) {
    assertNotNull(failure)
    assertEquals(AccountProblem.Unauthorized, failure.problem)
    assertEquals("unauthorized", failure.safeCode)
}

private const val CURRENT_PASSWORD = "Correct-Horse-42"
private const val NEW_PASSWORD = "New-Correct-Horse-43"
