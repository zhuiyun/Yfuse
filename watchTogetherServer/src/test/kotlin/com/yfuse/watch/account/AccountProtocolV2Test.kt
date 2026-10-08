package com.yfuse.watch.account

import com.yfuse.watch.watchTogetherModule
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountProtocolV2Test {
    @Test
    fun prelogin_answers_unknown_names_like_upgraded_accounts() =
        accountApp {
            val ghost = prelogin("ghost-user")
            assertEquals(AUTH_PROTOCOL_DERIVED_KEY, ghost.int("authProtocol"))
            assertEquals("PBKDF2-HMAC-SHA256", ghost.string("kdf"))
            assertEquals(600_000, ghost.int("kdfIterations"))
            assertEquals(ghost, prelogin("GHOST-USER"), "The decoy is stable for a name")
            assertNotEquals(ghost.string("kdfSalt"), prelogin("other-ghost").string("kdfSalt"))

            registerV1("legacy")
            assertEquals(JsonObject(mapOf("authProtocol" to Json.parseToJsonElement("1"))), prelogin("legacy"))

            registerV2("modern", authKey(1), kdfSalt(7))
            val modern = prelogin("Modern")
            assertEquals(AUTH_PROTOCOL_DERIVED_KEY, modern.int("authProtocol"))
            assertEquals(b64(kdfSalt(7)), modern.string("kdfSalt"))
            assertEquals(KDF_ITERATIONS, modern.int("kdfIterations"))
        }

    @Test
    fun a_protocol_2_account_never_accepts_the_password_and_returns_its_vault() =
        accountApp {
            val registered = registerV2("alice", authKey(1), kdfSalt(1))
            assertEquals(AUTH_PROTOCOL_DERIVED_KEY, registered.int("authProtocol"))
            assertEquals(vaultJson(1, 9), registered.getValue("vault"))

            val login = login("""{"username":"ALICE","authKey":"${authKey(1)}"}""")
            assertEquals(HttpStatusCode.OK, login.status)
            assertEquals(vaultJson(1, 9), login.json().getValue("vault"))

            val wrongKey = login("""{"username":"alice","authKey":"${authKey(2)}"}""")
            assertEquals(HttpStatusCode.Unauthorized, wrongKey.status)
            assertEquals("invalid_credentials", wrongKey.errorCode())

            val oldClient = login("""{"username":"alice","password":"$PASSWORD"}""")
            assertEquals(HttpStatusCode.Forbidden, oldClient.status)
            assertEquals("client_upgrade_required", oldClient.errorCode())

            val both = login("""{"username":"alice","password":"$PASSWORD","authKey":"${authKey(1)}"}""")
            assertEquals(HttpStatusCode.Unauthorized, both.status)

            val passwordChange =
                client.put("/api/v1/account/password") {
                    secureJson(
                        """
                        {"currentPassword":"$PASSWORD","newPassword":"Another-Password-9","expectedSyncVersion":0,
                         "keyVersion":1,"wrapVersion":1,"wrapKdf":"PBKDF2-HMAC-SHA256","wrapIterations":600000,
                         "wrappedVaultKey":"${b64(ByteArray(48))}","wrapSalt":"${b64(ByteArray(16))}",
                         "wrapNonce":"${b64(ByteArray(12))}"}
                        """.trimIndent(),
                    )
                    bearer(registered.string("accessToken"))
                }
            assertEquals(HttpStatusCode.Forbidden, passwordChange.status)
            assertEquals("client_upgrade_required", passwordChange.errorCode())
        }

    @Test
    fun registration_rejects_mixed_or_incomplete_credentials() =
        accountApp {
            val both =
                register(
                    """{"username":"bob","password":"$PASSWORD","authKey":"${authKey(1)}",
                    "kdfSalt":"${b64(kdfSalt(1))}","kdfIterations":$KDF_ITERATIONS,"vault":${vaultJson(1, 1)}}""",
                )
            assertEquals(HttpStatusCode.BadRequest, both.status)
            assertEquals("credentials_ambiguous", both.errorCode())

            val noVault =
                register(
                    """{"username":"bob","authKey":"${authKey(1)}","kdfSalt":"${b64(kdfSalt(1))}",
                    "kdfIterations":$KDF_ITERATIONS}""",
                )
            assertEquals("vault_required", noVault.errorCode())

            val weakKdf =
                register(
                    """{"username":"bob","authKey":"${authKey(1)}","kdfSalt":"${b64(kdfSalt(1))}",
                    "kdfIterations":1000,"vault":${vaultJson(1, 1)}}""",
                )
            assertEquals("kdf_iterations_invalid", weakKdf.errorCode())

            val nonCanonicalKey = authKey(1).dropLast(1) + "B"
            val badKey =
                register(
                    """{"username":"bob","authKey":"$nonCanonicalKey","kdfSalt":"${b64(kdfSalt(1))}",
                    "kdfIterations":$KDF_ITERATIONS,"vault":${vaultJson(1, 1)}}""",
                )
            assertEquals("auth_key_invalid", badKey.errorCode())
        }

    @Test
    fun clearing_the_cloud_keeps_the_vault_so_every_device_opens_the_same_key() =
        accountApp {
            val registered = registerV2("carol", authKey(3), kdfSalt(3))
            val token = registered.string("accessToken")

            val wrapped = putSync(token, syncBody(0, nonce = 1, keyVersion = 1, wrapped = true))
            assertEquals(HttpStatusCode.BadRequest, wrapped.status)
            assertEquals("sync_key_wrap_forbidden", wrapped.errorCode())

            val staleKey = putSync(token, syncBody(0, nonce = 1, keyVersion = 2))
            assertEquals(HttpStatusCode.Conflict, staleKey.status)
            assertEquals("sync_key_version_conflict", staleKey.errorCode())

            val saved = putSync(token, syncBody(0, nonce = 1, keyVersion = 1))
            assertEquals(HttpStatusCode.OK, saved.status)
            assertFalse(saved.bodyAsText().contains("wrappedVaultKey"))

            val cleared = client.delete("/api/v1/account/sync") { secureBearer(token) }
            assertEquals(HttpStatusCode.OK, cleared.status)

            val again = login("""{"username":"carol","authKey":"${authKey(3)}"}""").json()
            assertEquals(vaultJson(1, 9), again.getValue("vault"))
        }

    @Test
    fun a_device_that_already_holds_the_current_version_downloads_nothing() =
        accountApp {
            val token = registerV2("judy", authKey(1), kdfSalt(1)).string("accessToken")
            assertEquals(HttpStatusCode.OK, putSync(token, syncBody(0, nonce = 1, keyVersion = 1)).status)

            val unchanged = client.get("/api/v1/account/sync?knownVersion=1") { secureBearer(token) }.json()
            assertEquals(1L, unchanged.long("version"))
            assertTrue(
                unchanged
                    .getValue("unchanged")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertFalse(unchanged.containsKey("payload"))

            val newer = client.get("/api/v1/account/sync?knownVersion=7") { secureBearer(token) }.json()
            assertTrue(newer.containsKey("payload"))
            assertFalse(newer.containsKey("unchanged"))

            val invalid = client.get("/api/v1/account/sync?knownVersion=abc") { secureBearer(token) }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)

            client.delete("/api/v1/account/sync") { secureBearer(token) }
            val cleared = client.get("/api/v1/account/sync?knownVersion=2") { secureBearer(token) }.json()
            assertFalse(cleared.containsKey("unchanged"), "A cleared cloud is never reported as unchanged")
        }

    @Test
    fun rekey_upgrades_a_protocol_1_account_and_signs_every_device_out() =
        accountApp {
            val legacy = registerV1("dave")
            val legacyToken = legacy.string("accessToken")
            val otherDevice = login("""{"username":"dave","password":"$PASSWORD"}""").json().string("accessToken")
            assertEquals(
                HttpStatusCode.OK,
                putSync(legacyToken, syncBody(0, nonce = 1, keyVersion = 1, wrapped = true)).status,
            )

            val missingSync = rekey(legacyToken, rekeyBody(currentPassword = PASSWORD, vaultKeyVersion = 2))
            assertEquals(HttpStatusCode.Conflict, missingSync.status)
            assertEquals("sync_reencryption_required", missingSync.errorCode())

            val sameKeyVersion =
                rekey(legacyToken, rekeyBody(currentPassword = PASSWORD, vaultKeyVersion = 1, sync = syncBody(1, 2, 1)))
            assertEquals("vault_key_version_conflict", sameKeyVersion.errorCode())

            val staleBase =
                rekey(legacyToken, rekeyBody(currentPassword = PASSWORD, vaultKeyVersion = 2, sync = syncBody(0, 2, 2)))
            assertEquals("sync_version_conflict", staleBase.errorCode())
            assertEquals(
                1L,
                staleBase
                    .json()
                    .getValue("error")
                    .jsonObject
                    .long("currentVersion"),
            )

            val wrongPassword =
                rekey(
                    legacyToken,
                    rekeyBody(currentPassword = "Wrong-Password-1", vaultKeyVersion = 2, sync = syncBody(1, 2, 2)),
                )
            assertEquals("current_password_invalid", wrongPassword.errorCode())

            val upgraded =
                rekey(legacyToken, rekeyBody(currentPassword = PASSWORD, vaultKeyVersion = 2, sync = syncBody(1, 2, 2)))
            assertEquals(HttpStatusCode.OK, upgraded.status)
            val upgradedJson = upgraded.json()
            assertEquals(AUTH_PROTOCOL_DERIVED_KEY, upgradedJson.int("authProtocol"))
            assertEquals(vaultJson(2, 9), upgradedJson.getValue("vault"))

            assertEquals(HttpStatusCode.Unauthorized, profile(legacyToken).status)
            assertEquals(HttpStatusCode.Unauthorized, profile(otherDevice).status)
            assertEquals(HttpStatusCode.OK, profile(upgradedJson.string("accessToken")).status)

            val sync =
                client
                    .get("/api/v1/account/sync") { secureBearer(upgradedJson.string("accessToken")) }
                    .json()
            assertEquals(2L, sync.long("version"))
            assertEquals(2, sync.getValue("payload").jsonObject.int("keyVersion"))
            assertFalse(sync.getValue("payload").jsonObject.containsKey("wrappedVaultKey"))

            assertEquals(b64(kdfSalt(5)), prelogin("dave").string("kdfSalt"))
            assertEquals("client_upgrade_required", login("""{"username":"dave","password":"$PASSWORD"}""").errorCode())
            assertEquals(HttpStatusCode.OK, login("""{"username":"dave","authKey":"${authKey(5)}"}""").status)
        }

    @Test
    fun an_upgrade_always_moves_past_the_key_version_protocol_1_used() =
        accountApp {
            val token = registerV1("ivan").string("accessToken")

            val reused = rekey(token, rekeyBody(currentPassword = PASSWORD, vaultKeyVersion = 1))
            assertEquals(HttpStatusCode.Conflict, reused.status)
            assertEquals("vault_key_version_conflict", reused.errorCode())
            assertEquals(
                1L,
                reused
                    .json()
                    .getValue("error")
                    .jsonObject
                    .long("currentVersion"),
            )

            assertEquals(
                HttpStatusCode.OK,
                rekey(token, rekeyBody(currentPassword = PASSWORD, vaultKeyVersion = 2)).status,
            )
        }

    @Test
    fun rekey_changes_a_protocol_2_password_with_the_current_auth_key() =
        accountApp {
            val token = registerV2("erin", authKey(1), kdfSalt(1)).string("accessToken")

            val wrongProof = rekey(token, rekeyBody(currentAuthKey = authKey(2), vaultKeyVersion = 2))
            assertEquals(HttpStatusCode.Forbidden, wrongProof.status)
            assertEquals("current_password_invalid", wrongProof.errorCode())

            val passwordProof = rekey(token, rekeyBody(currentPassword = PASSWORD, vaultKeyVersion = 2))
            assertEquals("current_password_invalid", passwordProof.errorCode())

            val changed = rekey(token, rekeyBody(currentAuthKey = authKey(1), vaultKeyVersion = 2))
            assertEquals(HttpStatusCode.OK, changed.status)
            assertEquals(HttpStatusCode.Unauthorized, login("""{"username":"erin","authKey":"${authKey(1)}"}""").status)
            assertEquals(
                vaultJson(2, 9),
                login("""{"username":"erin","authKey":"${authKey(5)}"}""").json().getValue("vault"),
            )
        }

    @Test
    fun deleting_a_protocol_2_account_needs_its_auth_key() =
        accountApp {
            val token = registerV2("frank", authKey(1), kdfSalt(1)).string("accessToken")

            val withPassword = deleteAccount(token, """{"password":"$PASSWORD"}""")
            assertEquals(HttpStatusCode.Forbidden, withPassword.status)
            assertEquals("current_password_invalid", withPassword.errorCode())

            assertEquals(HttpStatusCode.NoContent, deleteAccount(token, """{"authKey":"${authKey(1)}"}""").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                login("""{"username":"frank","authKey":"${authKey(1)}"}""").status,
            )
        }

    @Test
    fun refreshing_keeps_the_session_id_that_other_features_are_bound_to() =
        accountApp {
            val registered = registerV2("grace", authKey(1), kdfSalt(1))
            val before = sessionIds(registered.string("accessToken"))

            val refreshed =
                client
                    .post("/api/v1/auth/refresh") {
                        secureJson("""{"refreshToken":"${registered.string("refreshToken")}"}""")
                    }.json()

            assertEquals(before, sessionIds(refreshed.string("accessToken")))
            assertNotEquals(registered.string("accessToken"), refreshed.string("accessToken"))
        }

    @Test
    fun made_up_invites_and_unknown_password_logins_cost_no_hashing() {
        val hasher = CountingHasher()
        SqliteAccountStore.inMemory().use { store ->
            val service =
                AccountService(
                    store = store,
                    passwordHasher = hasher,
                    usernameFailureLimiter = UsernameFailureLimiter(),
                    syncUserRateLimiter = AccountRateLimiter(),
                    registrationPolicy = AccountRegistrationPolicy(enabled = false),
                )

            val invite =
                assertFailsWith<AccountServiceException> {
                    service.register(RegisterRequest("henry", PASSWORD, inviteCode = "made-up-invite-code"))
                }
            assertEquals(AccountProblem.InvitationInvalid, invite.problem)
            assertEquals(0, hasher.calls)

            assertFailsWith<AccountServiceException> {
                service.login(LoginRequest("nobody", PASSWORD))
            }
            assertEquals(0, hasher.calls)

            assertFailsWith<AccountServiceException> {
                service.login(LoginRequest("nobody", authKey = authKey(1)))
            }
            assertEquals(1, hasher.calls, "An auth key for an unknown name costs what a real one does")
        }
    }

    private fun accountApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { watchTogetherModule(accountBackend = AccountBackend.inMemoryForTests()) }
            block()
        }

    private suspend fun ApplicationTestBuilder.prelogin(username: String): JsonObject =
        client
            .post("/api/v1/auth/prelogin") { secureJson("""{"username":"$username"}""") }
            .also { assertEquals(HttpStatusCode.OK, it.status) }
            .json()

    private suspend fun ApplicationTestBuilder.register(body: String): HttpResponse =
        client.post("/api/v1/auth/register") { secureJson(body) }

    private suspend fun ApplicationTestBuilder.registerV1(username: String): JsonObject =
        register("""{"username":"$username","password":"$PASSWORD"}""")
            .also { assertEquals(HttpStatusCode.Created, it.status) }
            .json()
            .also { assertFalse(it.containsKey("authProtocol"), "Protocol 1 responses stay as old clients know them") }

    private suspend fun ApplicationTestBuilder.registerV2(
        username: String,
        authKey: String,
        kdfSalt: ByteArray,
    ): JsonObject =
        register(
            """{"username":"$username","authKey":"$authKey","kdfSalt":"${b64(kdfSalt)}",
            "kdfIterations":$KDF_ITERATIONS,"vault":${vaultJson(1, 9)}}""",
        ).also { assertEquals(HttpStatusCode.Created, it.status) }
            .json()

    private suspend fun ApplicationTestBuilder.login(body: String): HttpResponse =
        client.post("/api/v1/auth/login") { secureJson(body) }

    private suspend fun ApplicationTestBuilder.profile(token: String): HttpResponse =
        client.get("/api/v1/account/profile") { secureBearer(token) }

    private suspend fun ApplicationTestBuilder.putSync(
        token: String,
        body: String,
    ): HttpResponse =
        client.put("/api/v1/account/sync") {
            secureJson(body)
            bearer(token)
        }

    private suspend fun ApplicationTestBuilder.rekey(
        token: String,
        body: String,
    ): HttpResponse =
        client.post("/api/v1/account/rekey") {
            secureJson(body)
            bearer(token)
        }

    private suspend fun ApplicationTestBuilder.deleteAccount(
        token: String,
        body: String,
    ): HttpResponse =
        client.delete("/api/v1/account") {
            secureJson(body)
            bearer(token)
        }

    private suspend fun ApplicationTestBuilder.sessionIds(token: String): List<String> =
        Json
            .decodeFromString(
                AccountSessionsResponse.serializer(),
                client.get("/api/v1/account/sessions") { secureBearer(token) }.bodyAsText(),
            ).sessions
            .map(AccountSessionResponse::id)

    private class CountingHasher : PasswordHasher {
        private val delegate = Pbkdf2PasswordHasher(iterations = 1_000)
        var calls = 0

        override fun hash(password: String): PasswordDigest = delegate.hash(password).also { calls++ }

        override fun verify(
            password: String,
            expected: PasswordDigest,
        ): Boolean = delegate.verify(password, expected).also { calls++ }
    }

    private companion object {
        const val PASSWORD = "Correct-Horse-42"
        const val KDF_ITERATIONS = 600_000

        fun authKey(seed: Int): String = b64(ByteArray(32) { (seed * 31 + it).toByte() })

        fun kdfSalt(seed: Int): ByteArray = ByteArray(16) { (seed * 17 + it).toByte() }

        fun vaultJson(
            keyVersion: Int,
            seed: Int,
        ) = Json.parseToJsonElement(
            """{"keyVersion":$keyVersion,"wrapVersion":2,"nonce":"${b64(ByteArray(12) { seed.toByte() })}",
            "wrappedKey":"${b64(ByteArray(48) { (seed + it).toByte() })}"}""",
        )

        fun syncBody(
            baseVersion: Long,
            nonce: Int,
            keyVersion: Int,
            wrapped: Boolean = false,
        ): String {
            val wrap =
                if (!wrapped) {
                    ""
                } else {
                    ""","wrapVersion":1,"wrapKdf":"PBKDF2-HMAC-SHA256","wrapIterations":600000,
                    "wrappedVaultKey":"${b64(ByteArray(48))}","wrapSalt":"${b64(ByteArray(16))}",
                    "wrapNonce":"${b64(ByteArray(12) { 7 })}""""
                }
            return """{"baseVersion":$baseVersion,"payload":{"schemaVersion":1,"algorithm":"AES-256-GCM",
                "keyVersion":$keyVersion,"nonce":"${b64(ByteArray(12) { nonce.toByte() })}",
                "ciphertext":"${b64(ByteArray(40) { nonce.toByte() })}"$wrap}}"""
        }

        fun rekeyBody(
            currentPassword: String? = null,
            currentAuthKey: String? = null,
            vaultKeyVersion: Int,
            sync: String? = null,
        ): String =
            buildString {
                append("{")
                currentPassword?.let { append("\"currentPassword\":\"$it\",") }
                currentAuthKey?.let { append("\"currentAuthKey\":\"$it\",") }
                append("\"authKey\":\"${authKey(5)}\",\"kdfSalt\":\"${b64(kdfSalt(5))}\",")
                append("\"kdfIterations\":$KDF_ITERATIONS,\"vault\":${vaultJson(vaultKeyVersion, 9)}")
                sync?.let { append(",\"sync\":$it") }
                append("}")
            }
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

private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

private suspend fun HttpResponse.errorCode(): String =
    json()
        .getValue("error")
        .jsonObject
        .string("code")

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
