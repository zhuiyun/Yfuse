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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real routing, shared SQLite storage and credential/session transitions; ciphertext stays opaque. */
class AccountPlaybackRekeyTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun a_failed_session_insert_rolls_back_playback_vault_sync_and_credentials() =
        testApplication {
            val database = Files.createTempFile("yfuse-rekey-rollback", ".db").toFile().also { it.deleteOnExit() }
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.sqliteForTests(database, clock = { 1_700_000_000_000L }),
                )
            }
            val token = register().accessToken
            push(token, listOf(PlaybackPutItem(0, entity(1)))).expect(HttpStatusCode.OK)
            putSync(token).expect(HttpStatusCode.OK)
            val source = pull(token)
            val before = export(token)
            stage(token, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            stage(token, PlaybackRekeyBatch(MIGRATION, 2, source.changes.map(::replacement))).expect(HttpStatusCode.OK)
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TRIGGER reject_rekey_session BEFORE INSERT ON sessions
                        WHEN (SELECT key_version FROM account_vaults WHERE user_id = NEW.user_id) = 2
                        BEGIN SELECT RAISE(ABORT, 'injected session failure'); END
                        """.trimIndent(),
                    )
                }
            }
            rekey(token, request(MIGRATION, source.cursor)).expect(HttpStatusCode.InternalServerError)
            assertEquals(source, pull(token))
            assertEquals(before, export(token))
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { it.execute("DROP TRIGGER reject_rekey_session") }
            }
            rekey(token, request(MIGRATION, source.cursor)).expect(HttpStatusCode.OK)
        }

    @Test
    fun legacy_clients_are_refused_then_a_complete_migration_commits_with_sync_vault_and_sessions() =
        accountApp {
            val auth = register()
            val token = auth.accessToken
            val source = entity(1)
            push(token, listOf(PlaybackPutItem(0, source))).expect(HttpStatusCode.OK)
            putSync(token).expect(HttpStatusCode.OK)
            val original = pull(token)
            val before = export(token)

            rekey(token, request()).expect(HttpStatusCode.Conflict)
            assertEquals(original, pull(token))
            assertEquals(before, export(token))

            stage(token, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            val migrated = replacement(original.changes.single())
            stage(token, PlaybackRekeyBatch(MIGRATION, 2, listOf(migrated))).expect(HttpStatusCode.OK)
            val changed =
                rekey(
                    token,
                    request(MIGRATION, original.cursor),
                ).expect(HttpStatusCode.OK).decode<AuthResponse>()
            val after = pull(changed.accessToken)
            assertEquals(1, after.changes.size)
            assertEquals(migrated.entity.ciphertext, after.changes.single().ciphertext)
            assertEquals(migrated.entity.entityKey, after.changes.single().entityKey)
            assertEquals(2, after.changes.single().keyVersion)
            assertTrue(after.cursor > original.cursor)
            val exported = export(changed.accessToken)
            assertEquals(2, exported.vault?.keyVersion)
            assertEquals(2L, exported.encryptedSync.version)
            assertEquals(2, exported.encryptedSync.payload?.keyVersion)
            push(token, listOf(PlaybackPutItem(0, source))).expect(HttpStatusCode.Unauthorized)
            push(changed.accessToken, listOf(PlaybackPutItem(0, source))).expect(HttpStatusCode.BadRequest)
            assertEquals(after, pull(changed.accessToken))
        }

    @Test
    fun a_concurrent_old_key_write_and_an_incomplete_set_both_refuse_without_changing_any_live_state() =
        accountApp {
            val token = register().accessToken
            push(token, listOf(PlaybackPutItem(0, entity(1)), PlaybackPutItem(0, entity(2)))).expect(HttpStatusCode.OK)
            putSync(token).expect(HttpStatusCode.OK)
            val original = pull(token)
            stage(token, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            stage(
                token,
                PlaybackRekeyBatch(MIGRATION, 2, original.changes.map(::replacement)),
            ).expect(HttpStatusCode.OK)
            val first = original.changes.first()
            push(
                token,
                listOf(PlaybackPutItem(first.cursor, first.copy(mutationId = "concurrent-write"))),
            ).expect(HttpStatusCode.OK)
            val concurrent = pull(token)
            val before = export(token)
            rekey(token, request(MIGRATION, original.cursor)).expect(HttpStatusCode.Conflict)
            assertEquals(concurrent, pull(token))
            assertEquals(before, export(token))

            stage(token, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            stage(
                token,
                PlaybackRekeyBatch(MIGRATION, 2, listOf(replacement(concurrent.changes.first()))),
            ).expect(HttpStatusCode.OK)
            rekey(token, request(MIGRATION, concurrent.cursor)).expect(HttpStatusCode.Conflict)
            assertEquals(concurrent, pull(token))
            assertEquals(before, export(token))
            client
                .delete(
                    "/api/v1/account/playback/rekey/$MIGRATION",
                ) { secure(token) }
                .expect(HttpStatusCode.NoContent)
            stage(token, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            stage(
                token,
                PlaybackRekeyBatch(MIGRATION, 2, concurrent.changes.map(::replacement)),
            ).expect(HttpStatusCode.OK)
            rekey(token, request(MIGRATION, concurrent.cursor)).expect(HttpStatusCode.OK)
        }

    @Test
    fun staging_is_bound_to_the_session_migration_and_ttl() {
        var now = 1_700_000_000_000L
        accountApp(clock = { now }) {
            val registered = register()
            val token = registered.accessToken
            val other =
                client
                    .post("/api/v1/auth/login") {
                        secure(body = """{"username":"viewer","authKey":"$AUTH_KEY"}""")
                    }.expect(HttpStatusCode.OK)
                    .decode<AuthResponse>()
            push(token, listOf(PlaybackPutItem(0, entity(1)))).expect(HttpStatusCode.OK)
            putSync(token).expect(HttpStatusCode.OK)
            val original = pull(token)
            val batch = PlaybackRekeyBatch(MIGRATION, 2, original.changes.map(::replacement))
            stage(token, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            stage(other.accessToken, batch).expect(HttpStatusCode.Conflict)
            stage(token, batch.copy(migrationId = "B".repeat(43))).expect(HttpStatusCode.Conflict)
            stage(token, batch).expect(HttpStatusCode.OK)
            rekey(other.accessToken, request(MIGRATION, original.cursor)).expect(HttpStatusCode.Conflict)
            assertEquals(original, pull(token))

            now += 31 * 60_000L
            val refreshed =
                client
                    .post("/api/v1/auth/refresh") {
                        secure(body = json.encodeToString(RefreshRequest(registered.refreshToken)))
                    }.expect(HttpStatusCode.OK)
                    .decode<AuthResponse>()
            rekey(refreshed.accessToken, request(MIGRATION, original.cursor)).expect(HttpStatusCode.Conflict)
            assertEquals(original, pull(refreshed.accessToken))
            assertEquals(1, export(refreshed.accessToken).vault?.keyVersion)
            // An expired attempt can be discarded and restarted with the refreshed session.
            stage(refreshed.accessToken, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            stage(refreshed.accessToken, batch).expect(HttpStatusCode.OK)
            rekey(refreshed.accessToken, request(MIGRATION, original.cursor)).expect(HttpStatusCode.OK)
        }
    }

    @Test
    fun all_1024_records_fit_bounded_batches_and_oversized_batches_fail_without_dropping_source_records() =
        accountApp {
            val token = register().accessToken
            (1..1_024).map(::entity).chunked(64).forEach { batch ->
                push(token, batch.map { PlaybackPutItem(0, it) }).expect(HttpStatusCode.OK)
            }
            putSync(token).expect(HttpStatusCode.OK)
            val original = pullAll(token)
            assertEquals(1_024, original.changes.size)
            stage(token, PlaybackRekeyBatch(MIGRATION, 2)).expect(HttpStatusCode.OK)
            val replacements = original.changes.map(::replacement)
            stage(token, PlaybackRekeyBatch(MIGRATION, 2, replacements.take(65))).expect(HttpStatusCode.BadRequest)
            val huge =
                replacements
                    .take(
                        4,
                    ).map { it.copy(entity = it.entity.copy(ciphertext = b64(ByteArray(96 * 1024 + 16)))) }
            stage(token, PlaybackRekeyBatch(MIGRATION, 2, huge)).expect(HttpStatusCode.PayloadTooLarge)
            replacements.chunked(64).forEach { batch ->
                stage(token, PlaybackRekeyBatch(MIGRATION, 2, batch)).expect(HttpStatusCode.OK)
            }
            val changed =
                rekey(
                    token,
                    request(MIGRATION, original.cursor),
                ).expect(HttpStatusCode.OK).decode<AuthResponse>()
            val migrated = pullAll(changed.accessToken)
            assertEquals(1_024, migrated.changes.size)
            assertEquals(
                replacements.map { it.entity.entityKey }.toSet(),
                migrated.changes.map { it.entityKey }.toSet(),
            )
            assertEquals(replacements.map { it.entity.ciphertext }, migrated.changes.map { it.ciphertext })
            assertTrue(migrated.changes.all { it.keyVersion == 2 })
        }

    private fun accountApp(
        clock: () -> Long = { 1_700_000_000_000L },
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { watchTogetherModule(accountBackend = AccountBackend.inMemoryForTests(clock = clock)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.register(): AuthResponse =
        client
            .post("/api/v1/auth/register") {
                secure(
                    body =
                        json.encodeToString(
                            RegisterRequest(
                                username = "viewer",
                                authKey = AUTH_KEY,
                                kdfSalt = b64(ByteArray(16)),
                                kdfIterations = 600_000,
                                vault = vault(1),
                            ),
                        ),
                )
            }.expect(HttpStatusCode.Created)
            .decode()

    private suspend fun ApplicationTestBuilder.push(
        token: String,
        items: List<PlaybackPutItem>,
    ) = client.post("/api/v1/account/playback") {
        secure(token, json.encodeToString(PlaybackPushRequest(items)))
    }

    private suspend fun ApplicationTestBuilder.stage(
        token: String,
        batch: PlaybackRekeyBatch,
    ) = client.post("/api/v1/account/playback/rekey") {
        secure(token, json.encodeToString(batch))
    }

    private suspend fun ApplicationTestBuilder.pull(
        token: String,
        after: Long = 0,
    ): PlaybackDeltaResponse =
        client
            .get("/api/v1/account/playback/rekey?after=$after&limit=200") {
                secure(token)
            }.expect(HttpStatusCode.OK)
            .decode()

    private suspend fun ApplicationTestBuilder.pullAll(token: String): PlaybackDeltaResponse {
        var cursor = 0L
        val all = mutableListOf<EncryptedPlaybackEntity>()
        do {
            val page = pull(token, cursor)
            all += page.changes
            cursor = page.cursor
        } while (page.hasMore)
        return PlaybackDeltaResponse(cursor, all)
    }

    private suspend fun ApplicationTestBuilder.export(token: String): AccountExportResponse =
        client
            .get("/api/v1/account/export") {
                secure(token)
            }.expect(HttpStatusCode.OK)
            .decode()

    private suspend fun ApplicationTestBuilder.putSync(token: String) =
        client.put("/api/v1/account/sync") {
            secure(token, json.encodeToString(PutSyncRequest(0, sync(1))))
        }

    private suspend fun ApplicationTestBuilder.rekey(
        token: String,
        request: RekeyRequest,
    ) = client.post("/api/v1/account/rekey") {
        secure(token, json.encodeToString(request))
    }

    private fun request(
        migration: String? = null,
        cursor: Long? = null,
    ) = RekeyRequest(
        currentAuthKey = AUTH_KEY,
        authKey = b64(ByteArray(32) { 2 }),
        kdfSalt = b64(ByteArray(16) { 2 }),
        kdfIterations = 600_000,
        vault = vault(2),
        sync = PutSyncRequest(1, sync(2)),
        playbackMigrationId = migration,
        playbackCursor = cursor,
    )

    private fun vault(version: Int) =
        VaultEnvelope(version, 2, b64(ByteArray(12) { version.toByte() }), b64(ByteArray(48)))

    private fun sync(version: Int) =
        EncryptedSyncEnvelope(
            schemaVersion = 1,
            algorithm = "AES-256-GCM",
            keyVersion = version,
            nonce =
                b64(
                    ByteArray(
                        12,
                    ) {
                        version.toByte()
                    },
                ),
            ciphertext = b64(ByteArray(32)),
        )

    private fun entity(index: Int): EncryptedPlaybackEntity =
        EncryptedPlaybackEntity(
            entityKey = index.toString().padStart(43, 'A'),
            mutationId = "mutation-$index",
            nonce = b64(ByteArray(12)),
            ciphertext = b64(ByteArray(32) { index.toByte() }),
        )

    private fun replacement(source: EncryptedPlaybackEntity) =
        PlaybackRekeyItem(
            source.entityKey,
            source.cursor,
            source.copy(
                entityKey = source.entityKey.replace('A', 'B'),
                keyVersion = 2,
                nonce =
                    b64(
                        ByteArray(12) {
                            2
                        },
                    ),
                ciphertext = b64(ByteArray(32) { 5 }),
            ),
        )

    private fun HttpRequestBuilder.secure(
        token: String? = null,
        body: String? = null,
    ) {
        header("X-Forwarded-Proto", "https")
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

    private suspend fun HttpResponse.expect(status: HttpStatusCode): HttpResponse =
        also {
            assertEquals(status, this.status, bodyAsText())
        }

    private suspend inline fun <reified T> HttpResponse.decode(): T = json.decodeFromString(bodyAsText())

    private companion object {
        const val MIGRATION = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val AUTH_KEY = b64(ByteArray(32) { 1 })

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
