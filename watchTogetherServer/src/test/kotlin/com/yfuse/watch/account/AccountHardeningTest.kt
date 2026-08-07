package com.yfuse.watch.account

import com.yfuse.watch.watchTogetherModule
import com.yfuse.watch.resolveServerHost
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AccountHardeningTest {
    @Test
    fun server_binds_to_loopback_unless_host_is_explicitly_configured() {
        assertEquals("127.0.0.1", resolveServerHost(null))
        assertEquals("127.0.0.1", resolveServerHost("  "))
        assertEquals("0.0.0.0", resolveServerHost("0.0.0.0"))
    }

    @Test
    fun account_executor_uses_dedicated_threads_and_rejects_global_overload() = runBlocking {
        val executor = AccountWorkExecutor(
            AccountExecutionPolicy(workerThreads = 1, maxConcurrentOperations = 1),
        )
        val release = CountDownLatch(1)
        val entered = CompletableDeferred<String>()
        val first = async {
            executor.execute {
                entered.complete(Thread.currentThread().name)
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val workerName = withTimeout(2_000L) { entered.await() }
            assertTrue(workerName.startsWith("yfuse-account-"))
            val rejected = runCatching { executor.execute { Unit } }.exceptionOrNull()
            assertIs<AccountWorkRejectedException>(rejected)
            assertEquals(0, executor.availablePermits())
        } finally {
            release.countDown()
            first.await()
            executor.close()
        }
    }

    @Test
    fun normalized_username_failures_are_limited_across_ips_without_existence_leakage() {
        var nowEpochMs = 1_700_000_000_000L
        val usernameLimiter = UsernameFailureLimiter(
            policy = UsernameFailureLimitPolicy(
                maxFailuresPerWindow = 2,
                windowMs = 60_000L,
                maxTrackedUsernames = 20,
                cleanupIntervalMs = 1_000L,
            ),
            clock = { nowEpochMs },
        )
        testApplication {
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(
                        clock = { nowEpochMs },
                        usernameFailureLimiter = usernameLimiter,
                    ),
                    accountRateLimiter = permissiveIpLimiter { nowEpochMs },
                )
            }

            assertEquals(HttpStatusCode.Created, registerHard("Alice", "198.51.100.1").status)
            listOf("Alice" to "198.51.100.2", "alice" to "198.51.100.3").forEach {
                    (username, ip) ->
                val failed = loginHard(username, "Wrong-Pass-42", ip)
                assertEquals(HttpStatusCode.Unauthorized, failed.status)
                assertEquals("invalid_credentials", failed.errorCodeHard())
            }
            val existingLimited = loginHard("ALICE", TEST_PASSWORD, "198.51.100.4")
            assertEquals(HttpStatusCode.TooManyRequests, existingLimited.status)
            assertEquals("rate_limited", existingLimited.errorCodeHard())

            listOf("Ghost" to "198.51.100.5", "ghost" to "198.51.100.6").forEach {
                    (username, ip) ->
                val failed = loginHard(username, "Wrong-Pass-42", ip)
                assertEquals(HttpStatusCode.Unauthorized, failed.status)
                assertEquals("invalid_credentials", failed.errorCodeHard())
            }
            val unknownLimited = loginHard("GHOST", TEST_PASSWORD, "198.51.100.7")
            assertEquals(HttpStatusCode.TooManyRequests, unknownLimited.status)
            assertEquals(existingLimited.bodyAsText(), unknownLimited.bodyAsText())
            assertEquals("60", unknownLimited.headers[HttpHeaders.RetryAfter])

            nowEpochMs += 60_000L
            assertEquals(
                HttpStatusCode.OK,
                loginHard("alice", TEST_PASSWORD, "198.51.100.8").status,
            )
        }
    }

    @Test
    fun sync_read_limits_apply_independently_per_user_and_per_ip() {
        testApplication {
            val perUserLimiter = AccountRateLimiter(
                AccountRateLimitPolicy(
                    credentialAttemptsPerWindow = 20,
                    syncReadAttemptsPerWindow = 1,
                    syncWriteAttemptsPerWindow = 20,
                    maxTrackedEntries = 100,
                ),
            )
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(
                        syncUserRateLimiter = perUserLimiter,
                    ),
                    accountRateLimiter = AccountRateLimiter(
                        AccountRateLimitPolicy(
                            credentialAttemptsPerWindow = 20,
                            syncReadAttemptsPerWindow = 20,
                            syncWriteAttemptsPerWindow = 20,
                            maxTrackedEntries = 100,
                        ),
                    ),
                )
            }
            val token = registerHard("Alice", "198.51.100.1")
                .bodyAsText()
                .hardObject()
                .hardString("accessToken")
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/account/sync") {
                    secureBearerFrom("198.51.100.2", token)
                }.status,
            )
            val limitedAcrossIp = client.get("/api/v1/account/sync") {
                secureBearerFrom("198.51.100.3", token)
            }
            assertEquals(HttpStatusCode.TooManyRequests, limitedAcrossIp.status)
            assertEquals("rate_limited", limitedAcrossIp.errorCodeHard())
        }

        testApplication {
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(
                        syncUserRateLimiter = AccountRateLimiter(
                            AccountRateLimitPolicy(
                                credentialAttemptsPerWindow = 20,
                                syncReadAttemptsPerWindow = 20,
                                syncWriteAttemptsPerWindow = 20,
                                maxTrackedEntries = 100,
                            ),
                        ),
                    ),
                    accountRateLimiter = AccountRateLimiter(
                        AccountRateLimitPolicy(
                            credentialAttemptsPerWindow = 20,
                            syncReadAttemptsPerWindow = 1,
                            syncWriteAttemptsPerWindow = 20,
                            maxTrackedEntries = 100,
                        ),
                    ),
                )
            }
            val aliceToken = registerHard("Alice", "203.0.113.1")
                .bodyAsText().hardObject().hardString("accessToken")
            val bobToken = registerHard("Bob", "203.0.113.1")
                .bodyAsText().hardObject().hardString("accessToken")
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/account/sync") {
                    secureBearerFrom("203.0.113.9", aliceToken)
                }.status,
            )
            val limitedAcrossUsers = client.get("/api/v1/account/sync") {
                secureBearerFrom("203.0.113.9", bobToken)
            }
            assertEquals(HttpStatusCode.TooManyRequests, limitedAcrossUsers.status)
            assertEquals("rate_limited", limitedAcrossUsers.errorCodeHard())
        }
    }

    @Test
    fun only_the_most_recent_active_sessions_are_kept_per_user() {
        val database = hardeningDatabaseFile()
        lateinit var userId: String
        lateinit var registrationAccess: String
        lateinit var firstLoginAccess: String
        lateinit var secondLoginAccess: String
        try {
            testApplication {
                application {
                    watchTogetherModule(
                        accountBackend = AccountBackend.sqliteForTests(
                            databaseFile = database,
                            activeSessionsPerUserLimit = 2,
                        ),
                    )
                }
                val registration = registerHard("Alice", "192.0.2.1").bodyAsText().hardObject()
                userId = registration.hardUser().hardString("id")
                registrationAccess = registration.hardString("accessToken")
                firstLoginAccess = loginHard("Alice", TEST_PASSWORD, "192.0.2.1")
                    .bodyAsText().hardObject().hardString("accessToken")
                secondLoginAccess = loginHard("Alice", TEST_PASSWORD, "192.0.2.1")
                    .bodyAsText().hardObject().hardString("accessToken")

                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/api/v1/account/profile") {
                        secureBearerFrom("192.0.2.1", registrationAccess)
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/v1/account/profile") {
                        secureBearerFrom("192.0.2.1", firstLoginAccess)
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/v1/account/profile") {
                        secureBearerFrom("192.0.2.1", secondLoginAccess)
                    }.status,
                )
            }
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM sessions WHERE user_id = ? AND revoked_at_ms IS NULL",
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals(2, result.getInt(1))
                    }
                }
            }
        } finally {
            deleteHardeningDatabase(database)
        }
    }

    @Test
    fun nonce_history_has_per_user_quota_and_age_cleanup() {
        val database = hardeningDatabaseFile()
        var nowEpochMs = 1_700_000_000_000L
        try {
            testApplication {
                application {
                    watchTogetherModule(
                        accountBackend = AccountBackend.sqliteForTests(
                            databaseFile = database,
                            clock = { nowEpochMs },
                            nonceHistoryPerUserLimit = 2,
                            nonceHistoryRetentionMs = 1_000L,
                            nonceHistoryCleanupIntervalMs = 1L,
                        ),
                    )
                }
                val registration = registerHard("Alice", "192.0.2.1").bodyAsText().hardObject()
                val token = registration.hardString("accessToken")
                val userId = registration.hardUser().hardString("id")
                repeat(3) { index ->
                    val response = client.put("/api/v1/account/sync") {
                        secureJsonFromHard(
                            clientIp = "192.0.2.1",
                            body = hardSyncBody(
                                baseVersion = index.toLong(),
                                nonceByte = (index + 1).toByte(),
                                includeWrap = index == 0,
                            ),
                        )
                        bearerHard(token)
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                }
                assertEquals(2, nonceCount(database, userId))

                nowEpochMs += 2_000L
                val afterRetention = client.put("/api/v1/account/sync") {
                    secureJsonFromHard(
                        "192.0.2.1",
                        hardSyncBody(baseVersion = 3L, nonceByte = 4, includeWrap = false),
                    )
                    bearerHard(token)
                }
                assertEquals(HttpStatusCode.OK, afterRetention.status)
                assertEquals(1, nonceCount(database, userId))
            }
        } finally {
            deleteHardeningDatabase(database)
        }
    }

    @Test
    fun legacy_sqlite_schema_is_altered_and_key_wrap_metadata_is_backfilled() {
        val database = hardeningDatabaseFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE users (
                            id TEXT PRIMARY KEY,
                            username TEXT NOT NULL,
                            username_normalized TEXT NOT NULL UNIQUE,
                            password_salt BLOB NOT NULL,
                            password_hash BLOB NOT NULL,
                            password_iterations INTEGER NOT NULL,
                            nickname TEXT NOT NULL,
                            avatar_id INTEGER NOT NULL,
                            created_at_ms INTEGER NOT NULL,
                            updated_at_ms INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE sync_records (
                            user_id TEXT PRIMARY KEY,
                            version INTEGER NOT NULL,
                            schema_version INTEGER NOT NULL,
                            algorithm TEXT NOT NULL,
                            key_version INTEGER NOT NULL,
                            nonce BLOB NOT NULL,
                            ciphertext BLOB NOT NULL,
                            wrapped_vault_key BLOB,
                            wrap_salt BLOB,
                            wrap_nonce BLOB,
                            updated_at_ms INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE sync_nonce_history (
                            user_id TEXT NOT NULL,
                            key_version INTEGER NOT NULL,
                            nonce BLOB NOT NULL,
                            PRIMARY KEY(user_id, key_version, nonce)
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    """
                    INSERT INTO users VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, "legacy-user")
                    statement.setString(2, "Legacy")
                    statement.setString(3, "legacy")
                    statement.setBytes(4, ByteArray(16) { 6 })
                    statement.setBytes(5, ByteArray(32) { 7 })
                    statement.setInt(6, 1_000)
                    statement.setString(7, "Legacy")
                    statement.setInt(8, 0)
                    statement.setLong(9, 1L)
                    statement.setLong(10, 1L)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO sync_records VALUES (?, 1, 1, 'AES-256-GCM', 1, ?, ?, ?, ?, ?, 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, "legacy-user")
                    statement.setBytes(2, ByteArray(12) { 1 })
                    statement.setBytes(3, ByteArray(32) { 2 })
                    statement.setBytes(4, ByteArray(48) { 3 })
                    statement.setBytes(5, ByteArray(16) { 4 })
                    statement.setBytes(6, ByteArray(12) { 5 })
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO sync_nonce_history VALUES (?, 1, ?)",
                ).use { statement ->
                    statement.setString(1, "legacy-user")
                    statement.setBytes(2, ByteArray(12) { 1 })
                    statement.executeUpdate()
                }
            }

            AccountBackend.sqliteForTests(database).close()

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT wrap_version, wrap_kdf, wrap_iterations FROM sync_records",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt("wrap_version"))
                        assertEquals("PBKDF2-HMAC-SHA256", result.getString("wrap_kdf"))
                        assertEquals(600_000, result.getInt("wrap_iterations"))
                    }
                    statement.executeQuery(
                        "SELECT recorded_at_ms FROM sync_nonce_history",
                    ).use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getLong("recorded_at_ms") > 0L)
                    }
                    statement.executeQuery(
                        "SELECT version, updated_at_ms FROM sync_revisions WHERE user_id = 'legacy-user'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1L, result.getLong("version"))
                        assertEquals(1L, result.getLong("updated_at_ms"))
                    }
                }
            }
        } finally {
            deleteHardeningDatabase(database)
        }
    }

    @Test
    fun registration_can_be_closed_and_user_capacity_is_enforced_before_password_hashing() {
        assertEquals(
            AccountRegistrationPolicy(enabled = false, maxUsers = 7),
            AccountRegistrationPolicy.fromEnvironment(
                mapOf(
                    "ACCOUNT_REGISTRATION_ENABLED" to "false",
                    "ACCOUNT_MAX_USERS" to "7",
                ),
            ),
        )
        testApplication {
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(
                        registrationPolicy = AccountRegistrationPolicy(enabled = false),
                    ),
                )
            }
            val closed = registerHard("Alice", "192.0.2.1")
            assertEquals(HttpStatusCode.ServiceUnavailable, closed.status)
            assertEquals("registration_closed", closed.errorCodeHard())
        }

        testApplication {
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(
                        registrationPolicy = AccountRegistrationPolicy(maxUsers = 1),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Created, registerHard("Alice", "192.0.2.1").status)
            val full = registerHard("Bob", "192.0.2.2")
            assertEquals(HttpStatusCode.ServiceUnavailable, full.status)
            assertEquals("registration_closed", full.errorCodeHard())
            assertEquals(
                HttpStatusCode.OK,
                loginHard("Alice", TEST_PASSWORD, "192.0.2.3").status,
            )

        }
    }

    @Test
    fun password_change_is_atomic_rewraps_only_the_vault_key_and_rotates_all_sessions() {
        testApplication {
            application {
                watchTogetherModule(accountBackend = AccountBackend.inMemoryForTests())
            }
            val registration = registerHard("Alice", "192.0.2.1").bodyAsText().hardObject()
            val oldAccess = registration.hardString("accessToken")
            val oldRefresh = registration.hardString("refreshToken")
            val secondSession = loginHard("Alice", TEST_PASSWORD, "192.0.2.2")
                .bodyAsText().hardObject()
            val secondAccess = secondSession.hardString("accessToken")
            val secondRefresh = secondSession.hardString("refreshToken")

            val initialSync = client.put("/api/v1/account/sync") {
                secureJsonFromHard("192.0.2.1", hardSyncBody(0L, 1, includeWrap = true))
                bearerHard(oldAccess)
            }
            assertEquals(HttpStatusCode.OK, initialSync.status)
            val originalPayload = initialSync.bodyAsText().hardObject().getValue("payload").jsonObject
            val originalCiphertext = originalPayload.hardString("ciphertext")
            val originalNonce = originalPayload.hardString("nonce")

            val conflict = client.put("/api/v1/account/password") {
                secureJsonFromHard(
                    "192.0.2.1",
                    hardPasswordBody(
                        currentPassword = TEST_PASSWORD,
                        newPassword = "New-Correct-Horse-43",
                        expectedSyncVersion = 0L,
                        wrapperByte = 21,
                    ),
                )
                bearerHard(oldAccess)
            }
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("sync_version_conflict", conflict.errorCodeHard())
            assertEquals(
                HttpStatusCode.OK,
                loginHard("Alice", TEST_PASSWORD, "192.0.2.3").status,
            )

            val keyVersionConflict = client.put("/api/v1/account/password") {
                secureJsonFromHard(
                    "192.0.2.1",
                    hardPasswordBody(
                        currentPassword = TEST_PASSWORD,
                        newPassword = "New-Correct-Horse-43",
                        expectedSyncVersion = 1L,
                        wrapperByte = 21,
                        keyVersion = 2,
                    ),
                )
                bearerHard(oldAccess)
            }
            assertEquals(HttpStatusCode.Conflict, keyVersionConflict.status)
            assertEquals("sync_key_version_conflict", keyVersionConflict.errorCodeHard())

            val wrongCurrent = client.put("/api/v1/account/password") {
                secureJsonFromHard(
                    "192.0.2.1",
                    hardPasswordBody(
                        currentPassword = "Wrong-Current-42",
                        newPassword = "New-Correct-Horse-43",
                        expectedSyncVersion = 1L,
                        wrapperByte = 21,
                    ),
                )
                bearerHard(oldAccess)
            }
            assertEquals(HttpStatusCode.Forbidden, wrongCurrent.status)
            assertEquals("current_password_invalid", wrongCurrent.errorCodeHard())

            val changed = client.put("/api/v1/account/password") {
                secureJsonFromHard(
                    "192.0.2.1",
                    hardPasswordBody(
                        currentPassword = TEST_PASSWORD,
                        newPassword = "New-Correct-Horse-43",
                        expectedSyncVersion = 1L,
                        wrapperByte = 22,
                    ),
                )
                bearerHard(oldAccess)
            }
            assertEquals(HttpStatusCode.OK, changed.status)
            val replacementAuth = changed.bodyAsText().hardObject()
            val newAccess = replacementAuth.hardString("accessToken")
            assertNotEquals(oldAccess, newAccess)

            listOf(oldAccess, secondAccess).forEach { access ->
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/api/v1/account/profile") {
                        secureBearerFrom("192.0.2.8", access)
                    }.status,
                )
            }
            listOf(oldRefresh, secondRefresh).forEach { refresh ->
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.post("/api/v1/auth/refresh") {
                        secureJsonFromHard("192.0.2.8", """{"refreshToken":"$refresh"}""")
                    }.status,
                )
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                loginHard("Alice", TEST_PASSWORD, "192.0.2.9").status,
            )
            assertEquals(
                HttpStatusCode.OK,
                loginHard("Alice", "New-Correct-Horse-43", "192.0.2.10").status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/account/profile") {
                    secureBearerFrom("192.0.2.11", newAccess)
                }.status,
            )

            val syncAfter = client.get("/api/v1/account/sync") {
                secureBearerFrom("192.0.2.11", newAccess)
            }
            assertEquals(HttpStatusCode.OK, syncAfter.status)
            val afterPayload = syncAfter.bodyAsText().hardObject().getValue("payload").jsonObject
            assertEquals(1, syncAfter.bodyAsText().hardObject().getValue("version").jsonPrimitive.content.toInt())
            assertEquals(originalCiphertext, afterPayload.hardString("ciphertext"))
            assertEquals(originalNonce, afterPayload.hardString("nonce"))
            assertEquals(hardB64(ByteArray(48) { 22 }), afterPayload.hardString("wrappedVaultKey"))
        }
    }

    @Test
    fun tombstone_revision_survives_restart_and_guards_put_and_password_change() {
        val database = hardeningDatabaseFile()
        try {
            testApplication {
                application {
                    watchTogetherModule(
                        accountBackend = AccountBackend.sqliteForTests(database),
                    )
                }
                val access = registerHard("Alice", "192.0.2.1")
                    .bodyAsText().hardObject().hardString("accessToken")
                val first = client.put("/api/v1/account/sync") {
                    secureJsonFromHard("192.0.2.1", hardSyncBody(0L, 1, includeWrap = true))
                    bearerHard(access)
                }
                assertEquals(HttpStatusCode.OK, first.status)
                assertEquals(
                    1L,
                    first.bodyAsText().hardObject().getValue("version").jsonPrimitive.content.toLong(),
                )
                val deleted = client.delete("/api/v1/account/sync") {
                    secureBearerFrom("192.0.2.1", access)
                }
                assertEquals(HttpStatusCode.OK, deleted.status)
                val tombstone = deleted.bodyAsText().hardObject()
                assertEquals(2L, tombstone.getValue("version").jsonPrimitive.content.toLong())
                assertFalse(tombstone.containsKey("payload"))
            }

            testApplication {
                application {
                    watchTogetherModule(
                        accountBackend = AccountBackend.sqliteForTests(database),
                    )
                }
                val access = loginHard("Alice", TEST_PASSWORD, "192.0.2.2")
                    .bodyAsText().hardObject().hardString("accessToken")
                val persisted = client.get("/api/v1/account/sync") {
                    secureBearerFrom("192.0.2.2", access)
                }
                val persistedJson = persisted.bodyAsText().hardObject()
                assertEquals(2L, persistedJson.getValue("version").jsonPrimitive.content.toLong())
                assertFalse(persistedJson.containsKey("payload"))

                val stalePut = client.put("/api/v1/account/sync") {
                    secureJsonFromHard("192.0.2.2", hardSyncBody(1L, 2, includeWrap = true))
                    bearerHard(access)
                }
                assertEquals(HttpStatusCode.Conflict, stalePut.status)
                assertEquals("sync_version_conflict", stalePut.errorCodeHard())

                val stalePasswordChange = client.put("/api/v1/account/password") {
                    secureJsonFromHard(
                        "192.0.2.2",
                        hardPasswordBody(
                            currentPassword = TEST_PASSWORD,
                            newPassword = "New-Correct-Horse-43",
                            expectedSyncVersion = 1L,
                            wrapperByte = 30,
                        ),
                    )
                    bearerHard(access)
                }
                assertEquals(HttpStatusCode.Conflict, stalePasswordChange.status)

                // A tombstone has no payload key version to rewrap. Matching its revision
                // changes only the password/session state and leaves the tombstone intact.
                val changed = client.put("/api/v1/account/password") {
                    secureJsonFromHard(
                        "192.0.2.2",
                        hardPasswordBody(
                            currentPassword = TEST_PASSWORD,
                            newPassword = "New-Correct-Horse-43",
                            expectedSyncVersion = 2L,
                            wrapperByte = 31,
                            keyVersion = 999,
                        ),
                    )
                    bearerHard(access)
                }
                assertEquals(HttpStatusCode.OK, changed.status)
                val replacementAccess = changed.bodyAsText().hardObject().hardString("accessToken")
                val afterPasswordChange = client.get("/api/v1/account/sync") {
                    secureBearerFrom("192.0.2.3", replacementAccess)
                }.bodyAsText().hardObject()
                assertEquals(
                    2L,
                    afterPasswordChange.getValue("version").jsonPrimitive.content.toLong(),
                )
                assertFalse(afterPasswordChange.containsKey("payload"))

                val rebuilt = client.put("/api/v1/account/sync") {
                    secureJsonFromHard("192.0.2.3", hardSyncBody(2L, 2, includeWrap = true))
                    bearerHard(replacementAccess)
                }
                assertEquals(HttpStatusCode.OK, rebuilt.status)
                assertEquals(
                    3L,
                    rebuilt.bodyAsText().hardObject().getValue("version").jsonPrimitive.content.toLong(),
                )
            }
        } finally {
            deleteHardeningDatabase(database)
        }
    }

    @Test
    fun username_failure_table_is_bounded_and_reclaims_expired_entries() {
        var nowEpochMs = 0L
        val limiter = UsernameFailureLimiter(
            UsernameFailureLimitPolicy(
                maxFailuresPerWindow = 2,
                windowMs = 1_000L,
                maxTrackedUsernames = 2,
                cleanupIntervalMs = 10_000L,
            ),
            clock = { nowEpochMs },
        )
        assertEquals(RateLimitDecision.Allowed, limiter.checkOrReserve("alice"))
        assertEquals(RateLimitDecision.Allowed, limiter.checkOrReserve("bob"))
        assertIs<RateLimitDecision.Limited>(limiter.checkOrReserve("carol"))
        assertEquals(2, limiter.trackedUsernameCount())

        nowEpochMs = 1_000L
        assertEquals(RateLimitDecision.Allowed, limiter.checkOrReserve("carol"))
        assertEquals(1, limiter.trackedUsernameCount())
    }

    companion object {
        private const val TEST_PASSWORD = "Correct-Horse-42"
    }
}

private fun permissiveIpLimiter(clock: () -> Long): AccountRateLimiter = AccountRateLimiter(
    AccountRateLimitPolicy(
        credentialAttemptsPerWindow = 100,
        refreshAttemptsPerWindow = 100,
        syncReadAttemptsPerWindow = 100,
        syncWriteAttemptsPerWindow = 100,
        maxTrackedEntries = 1_000,
    ),
    clock,
)

private suspend fun ApplicationTestBuilder.registerHard(username: String, ip: String) =
    client.post("/api/v1/auth/register") {
        secureJsonFromHard(
            ip,
            """{"username":"$username","password":"Correct-Horse-42","nickname":"$username","avatarId":1}""",
        )
    }

private suspend fun ApplicationTestBuilder.loginHard(username: String, password: String, ip: String) =
    client.post("/api/v1/auth/login") {
        secureJsonFromHard(ip, """{"username":"$username","password":"$password"}""")
    }

private fun HttpRequestBuilder.secureJsonFromHard(clientIp: String, body: String) {
    header("X-Forwarded-Proto", "https")
    header("X-Forwarded-For", clientIp)
    contentType(ContentType.Application.Json)
    setBody(body)
}

private fun HttpRequestBuilder.secureBearerFrom(clientIp: String, token: String) {
    header("X-Forwarded-Proto", "https")
    header("X-Forwarded-For", clientIp)
    bearerHard(token)
}

private fun HttpRequestBuilder.bearerHard(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun io.ktor.client.statement.HttpResponse.errorCodeHard(): String =
    bodyAsText().hardObject().getValue("error").jsonObject.hardString("code")

private fun String.hardObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.hardString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.hardUser(): JsonObject = getValue("user").jsonObject

private fun hardSyncBody(baseVersion: Long, nonceByte: Byte, includeWrap: Boolean): String {
    val wrap = if (includeWrap) {
        """
        ,"wrapVersion":1,
        "wrapKdf":"PBKDF2-HMAC-SHA256",
        "wrapIterations":600000,
        "wrappedVaultKey":"${hardB64(ByteArray(48) { 5 })}",
        "wrapSalt":"${hardB64(ByteArray(16) { 6 })}",
        "wrapNonce":"${hardB64(ByteArray(12) { 7 })}"
        """.trimIndent()
    } else {
        ""
    }
    return """
        {
          "baseVersion":$baseVersion,
          "payload":{
            "schemaVersion":1,
            "algorithm":"AES-256-GCM",
            "keyVersion":1,
            "nonce":"${hardB64(ByteArray(12) { nonceByte })}",
            "ciphertext":"${hardB64(ByteArray(32) { 9 })}"
            $wrap
          }
        }
    """.trimIndent()
}

private fun hardPasswordBody(
    currentPassword: String,
    newPassword: String,
    expectedSyncVersion: Long,
    wrapperByte: Byte,
    keyVersion: Int = 1,
): String = """
    {
      "currentPassword":"$currentPassword",
      "newPassword":"$newPassword",
      "expectedSyncVersion":$expectedSyncVersion,
      "keyVersion":$keyVersion,
      "wrapVersion":1,
      "wrapKdf":"PBKDF2-HMAC-SHA256",
      "wrapIterations":600000,
      "wrappedVaultKey":"${hardB64(ByteArray(48) { wrapperByte })}",
      "wrapSalt":"${hardB64(ByteArray(16) { (wrapperByte + 1).toByte() })}",
      "wrapNonce":"${hardB64(ByteArray(12) { (wrapperByte + 2).toByte() })}"
    }
""".trimIndent()

private fun hardB64(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun hardeningDatabaseFile(): File {
    val file = Files.createTempFile("yfuse-account-hardening", ".db").toFile()
    check(file.delete())
    return file
}

private fun nonceCount(database: File, userId: String): Int =
    DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM sync_nonce_history WHERE user_id = ?",
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
    }

private fun deleteHardeningDatabase(database: File) {
    database.delete()
    File(database.absolutePath + "-wal").delete()
    File(database.absolutePath + "-shm").delete()
}
