package com.yfuse.watch.account

import com.yfuse.watch.watchTogetherModule
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountInviteIssuanceTest {
    @Test
    fun configuredIssuerGetsCapabilityAndCanIssueButOrdinaryUserCannot() =
        testApplication {
            application {
                watchTogetherModule(
                    accountBackend =
                        AccountBackend.inMemoryForTests(
                            registrationPolicy = policy(),
                        ),
                )
            }
            val issuer = register("zhuiyun")
            val ordinary = register("ordinary")

            assertTrue("invite:issue" in issuer.userCapabilities())
            assertTrue(ordinary.userCapabilities().isEmpty())
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/api/v1/account/invites") { secure() }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .post("/api/v1/account/invites") {
                        secure(ordinary.token())
                    }.status,
            )
            val issued = client.post("/api/v1/account/invites") { secure(issuer.token()) }
            assertEquals(HttpStatusCode.Created, issued.status)
            assertEquals("no-store", issued.headers[HttpHeaders.CacheControl])
            val body = issued.bodyAsText().json()
            assertEquals(
                43,
                body
                    .getValue("code")
                    .jsonPrimitive.content.length,
            )
            assertTrue(
                body
                    .getValue("expiresAtEpochMs")
                    .jsonPrimitive.content
                    .toLong() > 0L,
            )
        }

    @Test
    fun issuedInviteIsPersistedAsDigestAndRedeemsOnceAcrossRestart() {
        val database = Files.createTempDirectory("yfuse-invite-test").resolve("account.db").toFile()
        lateinit var code: String
        try {
            testApplication {
                application { watchTogetherModule(accountBackend = backend(database)) }
                val issuer = register("zhuiyun")
                code =
                    client
                        .post("/api/v1/account/invites") { secure(issuer.token()) }
                        .bodyAsText()
                        .json()
                        .getValue("code")
                        .jsonPrimitive.content
            }
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT length(invite_hash) FROM account_invites").use {
                        assertTrue(it.next())
                        assertEquals(32, it.getInt(1))
                    }
                    statement.executeQuery("PRAGMA table_info(account_invites)").use {
                        val columns = buildSet { while (it.next()) add(it.getString("name")) }
                        assertFalse("code" in columns)
                    }
                }
            }
            testApplication {
                application { watchTogetherModule(accountBackend = backend(database)) }
                assertEquals(HttpStatusCode.Created, registerResponse("invited", code).status)
                assertEquals(HttpStatusCode.Forbidden, registerResponse("replayed", code).status)
            }
        } finally {
            database.parentFile.deleteRecursively()
        }
    }

    @Test
    fun migratedIssuedInviteStaysRedeemedAfterInvitedAccountIsDeleted() {
        val database =
            Files
                .createTempDirectory("yfuse-invite-migration-test")
                .resolve("account.db")
                .toFile()
        lateinit var code: String
        lateinit var invitedAccessToken: String
        try {
            testApplication {
                application { watchTogetherModule(accountBackend = backend(database)) }
                val issuer = register("zhuiyun")
                code =
                    client
                        .post("/api/v1/account/invites") { secure(issuer.token()) }
                        .bodyAsText()
                        .json()
                        .getValue("code")
                        .jsonPrimitive.content
                val invited = registerResponse("invited", code)
                assertEquals(HttpStatusCode.Created, invited.status)
                invitedAccessToken = invited.bodyAsText().json().token()
            }

            replaceInviteTableWithLegacyConstraint(database)

            testApplication {
                application { watchTogetherModule(accountBackend = backend(database)) }
                val deleted =
                    client.delete("/api/v1/account") {
                        secure(invitedAccessToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"password":"Invite-Test-42"}""")
                    }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals(HttpStatusCode.Forbidden, registerResponse("replayed", code).status)
            }

            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT redeemed_by_user_id, redeemed_at_ms FROM account_invites",
                        ).use { result ->
                            assertTrue(result.next())
                            assertEquals(null, result.getString("redeemed_by_user_id"))
                            assertTrue(result.getLong("redeemed_at_ms") > 0L)
                            assertFalse(result.wasNull())
                        }
                }
            }
        } finally {
            database.parentFile.deleteRecursively()
        }
    }

    @Test
    fun concurrentRedemptionHasExactlyOneWinner() =
        testApplication {
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(registrationPolicy = policy()),
                )
            }
            val issuer = register("zhuiyun")
            val code =
                client
                    .post("/api/v1/account/invites") { secure(issuer.token()) }
                    .bodyAsText()
                    .json()
                    .getValue("code")
                    .jsonPrimitive.content
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    listOf("first", "second").map { username ->
                        pool.submit(
                            Callable { kotlinx.coroutines.runBlocking { registerResponse(username, code).status } },
                        )
                    }
                val statuses = futures.map { it.get() }
                assertEquals(1, statuses.count { it == HttpStatusCode.Created })
                assertEquals(1, statuses.count { it == HttpStatusCode.Forbidden })
            } finally {
                pool.shutdownNow()
            }
        }

    private fun backend(database: File): AccountBackend =
        AccountBackend.sqliteForTests(
            database,
            registrationPolicy = policy(),
        )

    private fun policy() =
        AccountRegistrationPolicy(
            enabled = true,
            inviteIssuerUsernames = setOf("zhuiyun"),
        )

    private fun replaceInviteTableWithLegacyConstraint(database: File) {
        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = OFF")
            }
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("DROP INDEX IF EXISTS account_invites_issuer_idx")
                    statement.execute(
                        """
                        CREATE TABLE account_invites_legacy (
                            invite_hash BLOB PRIMARY KEY CHECK(length(invite_hash) = 32),
                            issuer_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            created_at_ms INTEGER NOT NULL,
                            expires_at_ms INTEGER NOT NULL,
                            redeemed_by_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
                            redeemed_at_ms INTEGER,
                            revoked_at_ms INTEGER,
                            CHECK(expires_at_ms > created_at_ms),
                            CHECK((redeemed_by_user_id IS NULL) = (redeemed_at_ms IS NULL))
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO account_invites_legacy(
                            invite_hash, issuer_user_id, created_at_ms, expires_at_ms,
                            redeemed_by_user_id, redeemed_at_ms, revoked_at_ms
                        )
                        SELECT invite_hash, issuer_user_id, created_at_ms, expires_at_ms,
                               redeemed_by_user_id, redeemed_at_ms, revoked_at_ms
                        FROM account_invites
                        """.trimIndent(),
                    )
                    statement.execute("DROP TABLE account_invites")
                    statement.execute("ALTER TABLE account_invites_legacy RENAME TO account_invites")
                    statement.execute(
                        """
                        CREATE INDEX account_invites_issuer_idx
                        ON account_invites(issuer_user_id, created_at_ms)
                        """.trimIndent(),
                    )
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.register(username: String) =
        registerResponse(username).bodyAsText().json()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.registerResponse(
        username: String,
        invite: String? = null,
    ) = client.post("/api/v1/auth/register") {
        secure()
        contentType(ContentType.Application.Json)
        setBody(
            """{"username":"$username","password":"Invite-Test-42"${invite?.let {
                ",\"inviteCode\":\"$it\""
            }.orEmpty()}}""",
        )
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.secure(token: String? = null) {
    header("X-Forwarded-Proto", "https")
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
}

private fun String.json() = Json.parseToJsonElement(this).jsonObject

private fun kotlinx.serialization.json.JsonObject.token(): String = getValue("accessToken").jsonPrimitive.content

private fun kotlinx.serialization.json.JsonObject.userCapabilities(): Set<String> =
    getValue("user")
        .jsonObject["capabilities"]
        ?.jsonArray
        ?.map { it.jsonPrimitive.content }
        ?.toSet()
        .orEmpty()
