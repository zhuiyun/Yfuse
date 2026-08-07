package com.yfuse.watch.account

import com.yfuse.watch.watchTogetherModule
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
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class AccountApiTest {
    @Test
    fun account_routes_require_https_and_only_trust_a_loopback_proxy() = testApplication {
        application {
            watchTogetherModule(accountBackend = AccountBackend.inMemoryForTests())
        }

        val insecure = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(REGISTER_BODY)
        }
        assertEquals(HttpStatusCode.UpgradeRequired, insecure.status)
        assertEquals("https_required", insecure.errorCode())

        assertTrue(isSecureAccountTransport("https", "203.0.113.10", null))
        assertTrue(isSecureAccountTransport("http", "127.0.0.1", "https"))
        assertFalse(isSecureAccountTransport("http", "203.0.113.10", "https"))
        assertFalse(isSecureAccountTransport("http", "127.0.0.1", "http, https"))
    }

    @Test
    fun registration_login_and_profile_round_trip_without_storing_plaintext_secrets() {
        val database = temporaryDatabaseFile()
        lateinit var firstAccessToken: String
        lateinit var firstRefreshToken: String
        try {
            testApplication {
                application {
                    watchTogetherModule(
                        accountBackend = AccountBackend.sqliteForTests(database),
                    )
                }

                val registered = client.post("/api/v1/auth/register") {
                    secureJson(REGISTER_BODY)
                }
                assertEquals(HttpStatusCode.Created, registered.status)
                val registrationText = registered.bodyAsText()
                assertFalse(registrationText.contains(TEST_PASSWORD))
                val registration = registrationText.asObject()
                firstAccessToken = registration.string("accessToken")
                firstRefreshToken = registration.string("refreshToken")
                assertEquals("Alice", registration.user().string("username"))
                assertEquals("小鱼", registration.user().string("nickname"))
                assertEquals(2, registration.user().int("avatarId"))

                val duplicate = client.post("/api/v1/auth/register") {
                    secureJson(REGISTER_BODY)
                }
                assertEquals(HttpStatusCode.Conflict, duplicate.status)
                assertEquals("username_unavailable", duplicate.errorCode())

                val wrongPassword = client.post("/api/v1/auth/login") {
                    secureJson("""{"username":"alice","password":"WrongPassword-99"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
                assertEquals("invalid_credentials", wrongPassword.errorCode())
                assertFalse(wrongPassword.bodyAsText().contains("Alice"))

                val login = client.post("/api/v1/auth/login") {
                    secureJson("""{"username":"alice","password":"$TEST_PASSWORD"}""")
                }
                assertEquals(HttpStatusCode.OK, login.status)

                val profile = client.get("/api/v1/account/profile") {
                    secureBearer(firstAccessToken)
                }
                assertEquals(HttpStatusCode.OK, profile.status)
                assertEquals("小鱼", profile.bodyAsText().asObject().string("nickname"))

                val updated = client.put("/api/v1/account/profile") {
                    secureJson("""{"nickname":"一起看","avatarId":5}""")
                    bearer(firstAccessToken)
                }
                assertEquals(HttpStatusCode.OK, updated.status)
                assertEquals("一起看", updated.bodyAsText().asObject().string("nickname"))
                assertEquals(5, updated.bodyAsText().asObject().int("avatarId"))
            }

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT length(password_salt), length(password_hash) FROM users",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(16, result.getInt(1))
                        assertEquals(32, result.getInt(2))
                    }
                    statement.executeQuery(
                        "SELECT length(access_token_hash), length(refresh_token_hash) FROM sessions",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(32, result.getInt(1))
                        assertEquals(32, result.getInt(2))
                    }
                    statement.executeQuery("PRAGMA table_info(sessions)").use { result ->
                        val columns = buildSet {
                            while (result.next()) add(result.getString("name"))
                        }
                        assertFalse("access_token" in columns)
                        assertFalse("refresh_token" in columns)
                    }
                }
            }
            assertNotEquals(firstAccessToken, firstRefreshToken)
        } finally {
            deleteSqliteFiles(database)
        }
    }

    @Test
    fun refresh_rotates_both_tokens_and_logout_revokes_the_session() = testApplication {
        application {
            watchTogetherModule(accountBackend = AccountBackend.inMemoryForTests())
        }

        val registered = register()
        val oldAccess = registered.string("accessToken")
        val oldRefresh = registered.string("refreshToken")
        val refreshedResponse = client.post("/api/v1/auth/refresh") {
            secureJson("""{"refreshToken":"$oldRefresh"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshedResponse.status)
        val refreshed = refreshedResponse.bodyAsText().asObject()
        val newAccess = refreshed.string("accessToken")
        val newRefresh = refreshed.string("refreshToken")
        assertNotEquals(oldAccess, newAccess)
        assertNotEquals(oldRefresh, newRefresh)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/account/profile") { secureBearer(oldAccess) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/auth/refresh") {
                secureJson("""{"refreshToken":"$oldRefresh"}""")
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/account/profile") { secureBearer(newAccess) }.status,
        )

        val logout = client.post("/api/v1/auth/logout") { secureBearer(newAccess) }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/account/profile") { secureBearer(newAccess) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/auth/refresh") {
                secureJson("""{"refreshToken":"$newRefresh"}""")
            }.status,
        )
    }

    @Test
    fun expired_access_token_can_only_be_recovered_with_a_live_refresh_token() {
        var nowEpochMs = 1_700_000_000_000L
        testApplication {
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(clock = { nowEpochMs }),
                )
            }

            val registered = register()
            val access = registered.string("accessToken")
            val refresh = registered.string("refreshToken")
            nowEpochMs += 15 * 60_000L + 1L

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/account/profile") { secureBearer(access) }.status,
            )
            val recovered = client.post("/api/v1/auth/refresh") {
                secureJson("""{"refreshToken":"$refresh"}""")
            }
            assertEquals(HttpStatusCode.OK, recovered.status)
            val recoveredAccess = recovered.bodyAsText().asObject().string("accessToken")
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/account/profile") {
                    secureBearer(recoveredAccess)
                }.status,
            )
        }
    }

    @Test
    fun encrypted_sync_is_opaque_versioned_and_rejects_conflicts_or_nonce_reuse() =
        testApplication {
            application {
                watchTogetherModule(accountBackend = AccountBackend.inMemoryForTests())
            }

            val registration = register()
            val accessToken = registration.string("accessToken")
            val empty = client.get("/api/v1/account/sync") { secureBearer(accessToken) }
            assertEquals(HttpStatusCode.OK, empty.status)
            assertEquals(0L, empty.bodyAsText().asObject().long("version"))
            assertFalse(empty.bodyAsText().asObject().containsKey("payload"))

            val nonce1 = b64(ByteArray(12) { 1 })
            val nonce2 = b64(ByteArray(12) { 2 })
            val ciphertext = b64(ByteArray(64) { (it * 7).toByte() })
            val wrappedKey = b64(ByteArray(48) { (it + 5).toByte() })
            val wrapSalt = b64(ByteArray(16) { (it + 11).toByte() })
            val wrapNonce = b64(ByteArray(12) { (it + 17).toByte() })
            val missingFirstWrap = client.put("/api/v1/account/sync") {
                secureJson(syncBodyWithoutWrap(0, nonce1, ciphertext))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.BadRequest, missingFirstWrap.status)
            assertEquals("sync_key_wrap_required", missingFirstWrap.errorCode())

            val firstBody = syncBody(0, nonce1, ciphertext, wrappedKey, wrapSalt, wrapNonce)
            val first = client.put("/api/v1/account/sync") {
                secureJson(firstBody)
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.OK, first.status)
            val firstJson = first.bodyAsText().asObject()
            assertEquals(1L, firstJson.long("version"))
            assertEquals(ciphertext, firstJson["payload"]!!.jsonObject.string("ciphertext"))

            val fetched = client.get("/api/v1/account/sync") { secureBearer(accessToken) }
            assertEquals(firstJson["payload"], fetched.bodyAsText().asObject()["payload"])

            val stale = client.put("/api/v1/account/sync") {
                secureJson(syncBody(0, nonce2, ciphertext, wrappedKey, wrapSalt, wrapNonce))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("sync_version_conflict", stale.errorCode())
            assertEquals(1L, stale.bodyAsText().asObject()["error"]!!.jsonObject.long("currentVersion"))

            val reusedNonce = client.put("/api/v1/account/sync") {
                secureJson(syncBody(1, nonce1, ciphertext, wrappedKey, wrapSalt, wrapNonce))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.Conflict, reusedNonce.status)
            assertEquals("sync_nonce_reused", reusedNonce.errorCode())

            val second = client.put("/api/v1/account/sync") {
                secureJson(syncBodyWithoutWrap(1, nonce2, ciphertext))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.OK, second.status)
            val secondJson = second.bodyAsText().asObject()
            assertEquals(2L, secondJson.long("version"))
            assertEquals(
                wrappedKey,
                secondJson["payload"]!!.jsonObject.string("wrappedVaultKey"),
            )

            val changedKeyWithoutWrap = client.put("/api/v1/account/sync") {
                secureJson(syncBodyWithoutWrap(2, b64(ByteArray(12) { 3 }), ciphertext, keyVersion = 2))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.BadRequest, changedKeyWithoutWrap.status)
            assertEquals("sync_key_wrap_required", changedKeyWithoutWrap.errorCode())

            val malformed = client.put("/api/v1/account/sync") {
                secureJson(syncBody(2, "not+base64", ciphertext, wrappedKey, wrapSalt, wrapNonce))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals("sync_envelope_invalid", malformed.errorCode())

            val deleted = client.delete("/api/v1/account/sync") {
                secureBearer(accessToken)
            }
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertEquals("no-store", deleted.headers[HttpHeaders.CacheControl])
            val deletedJson = deleted.bodyAsText().asObject()
            assertEquals(3L, deletedJson.long("version"))
            assertFalse(deletedJson.containsKey("payload"))

            val afterDelete = client.get("/api/v1/account/sync") { secureBearer(accessToken) }
            assertEquals(3L, afterDelete.bodyAsText().asObject().long("version"))
            assertFalse(afterDelete.bodyAsText().asObject().containsKey("payload"))

            // A write prepared against the payload that was deleted must not recreate
            // version 3 and thereby pass an ABA-style compare-and-swap check.
            val staleAfterDelete = client.put("/api/v1/account/sync") {
                secureJson(syncBody(2, nonce1, ciphertext, wrappedKey, wrapSalt, wrapNonce))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.Conflict, staleAfterDelete.status)
            assertEquals(3L, staleAfterDelete.bodyAsText()
                .asObject()["error"]!!.jsonObject.long("currentVersion"))

            val deletedAgain = client.delete("/api/v1/account/sync") {
                secureBearer(accessToken)
            }
            assertEquals(HttpStatusCode.OK, deletedAgain.status)
            assertEquals(4L, deletedAgain.bodyAsText().asObject().long("version"))
            assertFalse(deletedAgain.bodyAsText().asObject().containsKey("payload"))
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/account/profile") { secureBearer(accessToken) }.status,
            )
            val reusedAfterDelete = client.put("/api/v1/account/sync") {
                secureJson(syncBody(4, nonce1, ciphertext, wrappedKey, wrapSalt, wrapNonce))
                bearer(accessToken)
            }
            assertEquals(HttpStatusCode.OK, reusedAfterDelete.status)
            assertEquals(5L, reusedAfterDelete.bodyAsText().asObject().long("version"))

        }

    @Test
    fun sqlite_data_survives_backend_restart_and_request_size_is_bounded() {
        val database = temporaryDatabaseFile()
        try {
            testApplication {
                application {
                    watchTogetherModule(accountBackend = AccountBackend.sqliteForTests(database))
                }
                assertEquals(HttpStatusCode.Created, registerResponse().status)
            }
            testApplication {
                application {
                    watchTogetherModule(accountBackend = AccountBackend.sqliteForTests(database))
                }
                val login = client.post("/api/v1/auth/login") {
                    secureJson("""{"username":"Alice","password":"$TEST_PASSWORD"}""")
                }
                assertEquals(HttpStatusCode.OK, login.status)

                val oversized = client.post("/api/v1/auth/login") {
                    secureJson("""{"username":"Alice","password":"${"x".repeat(385 * 1024)}"}""")
                }
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                assertTrue(oversized.bodyAsText().toByteArray().size < 1_024)
            }
        } finally {
            deleteSqliteFiles(database)
        }
    }

    @Test
    fun pbkdf2_hashes_use_unique_salts_and_verify_passwords() {
        val hasher = Pbkdf2PasswordHasher(iterations = 1_000)
        val first = hasher.hash(TEST_PASSWORD)
        val second = hasher.hash(TEST_PASSWORD)
        try {
            assertNotEquals(b64(first.salt), b64(second.salt))
            assertNotEquals(b64(first.hash), b64(second.hash))
            assertTrue(hasher.verify(TEST_PASSWORD, first))
            assertFalse(hasher.verify("Definitely-Wrong-99", first))
            assertEquals(32, first.hash.size)
        } finally {
            first.wipeForTest()
            second.wipeForTest()
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.register(): JsonObject =
        registerResponse().bodyAsText().asObject()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.registerResponse() =
        client.post("/api/v1/auth/register") { secureJson(REGISTER_BODY) }

    companion object {
        private const val TEST_PASSWORD = "Correct-Horse-42"
        private const val REGISTER_BODY =
            """{"username":"Alice","password":"$TEST_PASSWORD","nickname":"小鱼","avatarId":2}"""
    }
}

private fun HttpRequestBuilder.secureJson(body: String) {
    header("X-Forwarded-Proto", "https")
    contentType(ContentType.Application.Json)
    setBody(body)
}

private fun HttpRequestBuilder.secureBearer(token: String) {
    header("X-Forwarded-Proto", "https")
    bearer(token)
}

private fun HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private fun String.asObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

private fun JsonObject.user(): JsonObject = getValue("user").jsonObject

private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
    bodyAsText().asObject().getValue("error").jsonObject.string("code")

private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun syncBody(
    baseVersion: Long,
    nonce: String,
    ciphertext: String,
    wrappedVaultKey: String,
    wrapSalt: String,
    wrapNonce: String,
    schemaVersion: Int = 1,
    keyVersion: Int = 1,
    wrapIterations: Int = 600_000,
): String = """
    {
      "baseVersion":$baseVersion,
      "payload":{
        "schemaVersion":$schemaVersion,
        "algorithm":"AES-256-GCM",
        "keyVersion":$keyVersion,
        "nonce":"$nonce",
        "ciphertext":"$ciphertext",
        "wrapVersion":1,
        "wrapKdf":"PBKDF2-HMAC-SHA256",
        "wrapIterations":$wrapIterations,
        "wrappedVaultKey":"$wrappedVaultKey",
        "wrapSalt":"$wrapSalt",
        "wrapNonce":"$wrapNonce"
      }
    }
""".trimIndent()

private fun syncBodyWithoutWrap(
    baseVersion: Long,
    nonce: String,
    ciphertext: String,
    schemaVersion: Int = 1,
    keyVersion: Int = 1,
): String = """
    {
      "baseVersion":$baseVersion,
      "payload":{
        "schemaVersion":$schemaVersion,
        "algorithm":"AES-256-GCM",
        "keyVersion":$keyVersion,
        "nonce":"$nonce",
        "ciphertext":"$ciphertext"
      }
    }
""".trimIndent()

private fun temporaryDatabaseFile(): File {
    val file = Files.createTempFile("yfuse-account-test", ".db").toFile()
    check(file.delete())
    return file
}

private fun deleteSqliteFiles(database: File) {
    database.delete()
    File(database.absolutePath + "-wal").delete()
    File(database.absolutePath + "-shm").delete()
}

private fun PasswordDigest.wipeForTest() {
    salt.fill(0)
    hash.fill(0)
}
