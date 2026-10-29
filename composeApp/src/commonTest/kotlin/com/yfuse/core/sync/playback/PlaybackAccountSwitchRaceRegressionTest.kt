package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.AccountApi
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountUser
import com.yfuse.core.account.AuthResponse
import com.yfuse.core.account.PlaybackCloudApi
import com.yfuse.core.account.PlaybackVaultCipher
import com.yfuse.core.account.createAccountClient
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.sync.ServerSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlaybackAccountSwitchRaceRegressionTest {
    @Test
    fun an_in_flight_foreground_pull_from_account_a_cannot_advance_account_b_cursor() =
        runTest(timeout = 30.seconds) {
            val settings = MapSettings()
            val secureStore = TestSecureStore()
            val crypto = VaultCrypto()
            val personal = PersonalLibraryRepository(settings)
            val registry = ServerRegistry(settings, TestSecureStore())
            val tokens = AccountAccessTokenSource(ORIGIN)
            val embyClient = HttpClient(MockEngine { error("No media server is configured") })
            val emby = EmbyRepository(embyClient)
            var registrations = 0
            val accountClient =
                createAccountClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/api/v1/auth/prelogin" -> respond("", HttpStatusCode.NotFound)
                            "/api/v1/auth/register" -> {
                                val id = if (++registrations == 1) "account-a" else "account-b"
                                respondJson(
                                    Json.encodeToString(
                                        AuthResponse(
                                            user = AccountUser(id, id, id, 1, 0L, 0L),
                                            accessToken = "access-$id",
                                            accessExpiresAtEpochMs = Long.MAX_VALUE,
                                            refreshToken = "refresh-$id",
                                            refreshExpiresAtEpochMs = Long.MAX_VALUE,
                                        ),
                                    ),
                                )
                            }
                            "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
                            else -> error("Unexpected account route: ${request.url.encodedPath}")
                        }
                    },
                )
            val account =
                AccountRepository(
                    api = AccountApi(accountClient, ORIGIN),
                    secureStore = secureStore,
                    crypto = crypto,
                    registry = registry,
                    theme = ThemePreferences(settings),
                    userAgent = UserAgentPreferences(settings),
                    watch = WatchTogetherPreferences(settings),
                    danmaku = DanmakuPreferences(settings),
                    skip = SkipSegmentPreferences(settings),
                    serverSync = ServerSyncManager(emby, registry, settings),
                    accessTokenSource = tokens,
                    personal = personal,
                )
            val cipher = PlaybackVaultCipher(account, secureStore, crypto)
            val store = PlaybackSyncStore(settings, personal = personal)
            val heldPullStarted = CompletableDeferred<Unit>()
            val releaseOldResponse = CompletableDeferred<Unit>()
            val accountBRequestedAfter = CompletableDeferred<Long>()
            var accountAPulls = 0
            lateinit var accountAResponse: PlaybackDeltaResponse
            lateinit var accountBResponse: PlaybackDeltaResponse
            val cloudClient =
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { request ->
                                check(request.url.encodedPath == "/api/v1/account/playback")
                                check(request.method == HttpMethod.Get)
                                when (request.headers[HttpHeaders.Authorization]) {
                                    "Bearer access-account-a" -> {
                                        if (++accountAPulls == 1) {
                                            respondJson(Json.encodeToString(PlaybackDeltaResponse(cursor = 0L)))
                                        } else {
                                            // Authentication already succeeded before logout; delay only its response.
                                            heldPullStarted.complete(Unit)
                                            releaseOldResponse.await()
                                            respondJson(Json.encodeToString(accountAResponse))
                                        }
                                    }
                                    "Bearer access-account-b" -> {
                                        val after = request.url.parameters["after"]!!.toLong()
                                        accountBRequestedAfter.complete(after)
                                        val page =
                                            if (after < accountBResponse.cursor) {
                                                accountBResponse
                                            } else {
                                                accountBResponse.copy(changes = emptyList())
                                            }
                                        respondJson(Json.encodeToString(page))
                                    }
                                    else -> error("Unexpected playback account")
                                }
                            }
                        },
                    ),
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val syncScope =
                CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val manager =
                PlaybackSyncManager(
                    store = store,
                    cloud = PlaybackCloudApi(cloudClient, ORIGIN),
                    cipher = cipher,
                    accessTokens = tokens,
                    repo = emby,
                    registry = registry,
                    scope = syncScope,
                    personal = personal,
                )
            try {
                account.register("viewer_aa", "correct horse battery".toCharArray()).getOrThrow()
                accountAResponse = encryptedPage(cipher, mediaKey = "tmdb:1", cursor = 42L)
                manager.start()
                manager.state.first { it.lastSyncedAtEpochMs != null }

                manager.refreshNow()
                heldPullStarted.await()
                account.logout().getOrThrow()
                runCurrent()
                account.register("viewer_bb", "correct horse battery".toCharArray()).getOrThrow()
                runCurrent()

                // Verify the real collector, not the test, has rebound the shared store to B.
                assertEquals("account-b", settings.getStringOrNull("playback.cross_platform.account_user.v1"))
                assertEquals("account-b", cipher.currentUserId())
                assertTrue(cipher.hasKey())
                assertEquals(0L, store.cursor())
                accountBResponse = encryptedPage(cipher, mediaKey = "tmdb:2", cursor = 7L)

                releaseOldResponse.complete(Unit)
                assertEquals(
                    0L,
                    accountBRequestedAfter.await(),
                    "B's first pull must start at its own cursor, never the delayed A response's cursor 42",
                )
            } finally {
                manager.close()
                syncScope.cancel()
                account.close()
                registry.close()
                cloudClient.close()
                accountClient.close()
                embyClient.close()
            }
        }

    private fun encryptedPage(
        cipher: PlaybackVaultCipher,
        mediaKey: String,
        cursor: Long,
    ): PlaybackDeltaResponse {
        val document =
            PlaybackSyncDocument(
                state =
                    PlaybackStateRecord(
                        mediaKey = mediaKey,
                        positionMs = 600_000L,
                        durationMs = 3_600_000L,
                        deviceId = "remote-device",
                        lastPlayedAtEpochMs = 1_000L,
                    ),
            )
        val encrypted = assertNotNull(cipher.encrypt(document, mutationId = "mutation-$cursor"))
        return PlaybackDeltaResponse(cursor = cursor, changes = listOf(encrypted.copy(cursor = cursor)))
    }

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private companion object {
        const val ORIGIN = "https://account.example.test"
    }
}
