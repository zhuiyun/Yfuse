package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.AccountApi
import com.yfuse.core.account.AccountApiException
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
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
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.sync.ServerSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSyncManagerTest {
    @Test
    fun token_timeout_preserves_playback_and_retries_only_after_backoff() =
        runTest {
            var tokenRequests = 0
            val fixture =
                fixture(
                    tokenProvider = {
                        tokenRequests++
                        if (tokenRequests == 1) throw SocketTimeoutException("Account refresh timed out")
                        "access"
                    },
                )
            try {
                fixture.manager.start()
                runCurrent()

                assertEquals(1, tokenRequests)
                assertFalse(fixture.manager.state.value.syncing)
                assertNotNull(fixture.manager.state.value.error)
                assertIs<AccountState.SignedIn>(fixture.account.state.value)
                assertTrue(fixture.tokens.sessionAvailable.value)
                assertEquals(
                    12_000L,
                    fixture.store
                        .pending()
                        .single()
                        .document.state.positionMs,
                )

                fixture.recordStop(15_000L)
                advanceTimeBy(5_000L)
                runCurrent()
                assertEquals(1, tokenRequests, "A new playback event must not bypass token-refresh backoff")
                assertEquals(15_000L, fixture.manager.resumePositionMs(MEDIA_KEY))

                advanceTimeBy(25_000L)
                runCurrent()
                assertEquals(2, tokenRequests)
                assertTrue(fixture.store.pending().isEmpty())
                assertNull(fixture.manager.state.value.error)
                assertEquals(15_000L, fixture.manager.resumePositionMs(MEDIA_KEY))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun a_refresh_timeout_after_401_is_deferred_without_losing_local_progress() =
        runTest {
            var refreshRequests = 0
            var cloudRequests = 0
            val fixture =
                fixture(
                    refreshProvider = {
                        refreshRequests++
                        throw SocketTimeoutException("Forced account refresh timed out")
                    },
                    cloudHandler = {
                        cloudRequests++
                        unauthorized()
                    },
                )
            try {
                fixture.manager.start()
                runCurrent()

                assertEquals(1, refreshRequests)
                assertEquals(1, cloudRequests)
                assertFalse(fixture.manager.state.value.syncing)
                assertNotNull(fixture.manager.state.value.error)
                assertEquals(
                    12_000L,
                    fixture.store
                        .pending()
                        .single()
                        .document.state.positionMs,
                )
                assertIs<AccountState.SignedIn>(fixture.account.state.value)

                advanceTimeBy(29_999L)
                runCurrent()
                assertEquals(1, refreshRequests)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun a_401_can_refresh_once_and_upload_the_queued_progress() =
        runTest {
            var refreshRequests = 0
            var cloudRequests = 0
            val fixture =
                fixture(
                    refreshProvider = {
                        refreshRequests++
                        "refreshed"
                    },
                    cloudHandler = { request ->
                        cloudRequests++
                        if (request.headers[HttpHeaders.Authorization] == "Bearer refreshed") {
                            successfulPlaybackResponse(request)
                        } else {
                            unauthorized()
                        }
                    },
                )
            try {
                fixture.manager.start()
                runCurrent()

                assertEquals(1, refreshRequests)
                assertEquals(3, cloudRequests) // Initial 401, successful pull, successful push.
                assertTrue(fixture.store.pending().isEmpty())
                assertNull(fixture.manager.state.value.error)
                assertFalse(fixture.manager.state.value.syncing)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun a_second_401_is_deferred_instead_of_starting_a_refresh_loop() =
        runTest {
            var refreshRequests = 0
            var cloudRequests = 0
            val fixture =
                fixture(
                    refreshProvider = {
                        refreshRequests++
                        "refreshed"
                    },
                    cloudHandler = {
                        cloudRequests++
                        unauthorized()
                    },
                )
            try {
                fixture.manager.start()
                runCurrent()

                assertEquals(1, refreshRequests)
                assertEquals(2, cloudRequests)
                assertEquals(1, fixture.store.pending().size)
                assertNotNull(fixture.manager.state.value.error)
                assertFalse(fixture.manager.state.value.syncing)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun an_account_refresh_404_does_not_disable_the_playback_endpoint() =
        runTest {
            var tokenRequests = 0
            val fixture =
                fixture(
                    tokenProvider = {
                        tokenRequests++
                        if (tokenRequests == 1) {
                            throw AccountApiException(
                                "http_404",
                                "Refresh endpoint unavailable",
                                HttpStatusCode.NotFound,
                            )
                        }
                        "access"
                    },
                )
            try {
                fixture.manager.start()
                runCurrent()
                assertEquals(1, fixture.store.pending().size)

                advanceTimeBy(30_000L)
                runCurrent()
                assertEquals(2, tokenRequests)
                assertTrue(fixture.store.pending().isEmpty())
                assertNull(fixture.manager.state.value.error)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun token_cancellation_is_not_reported_as_a_network_failure_or_retried() =
        runTest {
            var tokenRequests = 0
            val fixture =
                fixture(
                    tokenProvider = {
                        tokenRequests++
                        throw CancellationException("Playback sync cancelled")
                    },
                )
            try {
                fixture.manager.start()
                runCurrent()
                advanceTimeBy(60_000L)
                runCurrent()

                assertEquals(1, tokenRequests)
                assertNull(fixture.manager.state.value.error)
                assertFalse(fixture.manager.state.value.syncing)
                assertEquals(1, fixture.store.pending().size)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun an_unavailable_token_does_not_leave_the_sync_indicator_running() =
        runTest {
            val fixture = fixture(tokenProvider = { null })
            try {
                fixture.manager.start()
                runCurrent()

                assertFalse(fixture.manager.state.value.syncing)
                assertNull(fixture.manager.state.value.error)
                assertEquals(1, fixture.store.pending().size)
            } finally {
                fixture.close()
            }
        }

    private suspend fun TestScope.fixture(
        tokenProvider: suspend () -> String? = { "access" },
        refreshProvider: suspend () -> String? = { "refreshed" },
        cloudHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { successfulPlaybackResponse(it) },
    ): Fixture {
        val settings = MapSettings()
        val secureStore = TestSecureStore()
        val registry = ServerRegistry(settings, TestSecureStore())
        val crypto = VaultCrypto()
        val embyClient = HttpClient(MockEngine { error("Unexpected Emby request") })
        val emby = EmbyRepository(embyClient)
        val tokens = AccountAccessTokenSource(ACCOUNT_ORIGIN)
        val accountClient =
            createAccountClient(
                MockEngine { request ->
                    check(request.url.encodedPath == "/api/v1/auth/register")
                    respondJson(
                        Json.encodeToString(
                            AuthResponse(
                                user = AccountUser("account", "viewer_01", "viewer", 1, 0L, 0L),
                                accessToken = "access",
                                accessExpiresAtEpochMs = Long.MAX_VALUE,
                                refreshToken = "refresh",
                                refreshExpiresAtEpochMs = Long.MAX_VALUE,
                            ),
                        ),
                    )
                },
            )
        val account =
            AccountRepository(
                api = AccountApi(accountClient, ACCOUNT_ORIGIN),
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
            )
        account.register("viewer_01", "correct horse battery".toCharArray()).getOrThrow()
        tokens.bind(tokenProvider, refreshProvider)
        val cloudClient =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler(cloudHandler)
                    },
                ),
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        val syncScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val store = PlaybackSyncStore(settings) { testScheduler.currentTime }
        store.updatePlayback(
            mediaKey = MEDIA_KEY,
            aliases = emptyList(),
            positionMs = 12_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "playing-session",
            serverId = null,
            serverItemId = null,
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )
        val manager =
            PlaybackSyncManager(
                store = store,
                cloud = PlaybackCloudApi(cloudClient, ACCOUNT_ORIGIN),
                cipher = PlaybackVaultCipher(account, secureStore, crypto),
                accessTokens = tokens,
                repo = emby,
                registry = registry,
                nowEpochMs = { testScheduler.currentTime },
                scope = syncScope,
            )
        return Fixture(manager, store, account, tokens, syncScope, listOf(accountClient, cloudClient, embyClient))
    }

    private class Fixture(
        val manager: PlaybackSyncManager,
        val store: PlaybackSyncStore,
        val account: AccountRepository,
        val tokens: AccountAccessTokenSource,
        private val scope: CoroutineScope,
        private val clients: List<HttpClient>,
    ) {
        fun recordStop(positionMs: Long) {
            manager.recordPlayback(
                mediaKey = MEDIA_KEY,
                aliases = emptyList(),
                positionMs = positionMs,
                durationMs = 100_000L,
                sessionId = "playing-session",
                serverId = null,
                serverItemId = null,
                trigger = PlaybackSyncTrigger.Stop,
            )
        }

        fun close() {
            scope.cancel()
            clients.forEach(HttpClient::close)
        }
    }

    private companion object {
        const val ACCOUNT_ORIGIN = "https://account.example.test"
        const val MEDIA_KEY = "tmdb:1"

        fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
            respond(
                body,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

        fun MockRequestHandleScope.unauthorized(): HttpResponseData =
            respond("Unauthorized", HttpStatusCode.Unauthorized)

        suspend fun MockRequestHandleScope.successfulPlaybackResponse(request: HttpRequestData): HttpResponseData {
            check(request.url.encodedPath == "/api/v1/account/playback")
            if (request.method == HttpMethod.Get) {
                return respondJson(Json.encodeToString(PlaybackDeltaResponse(cursor = 0L)))
            }
            val push = Json.decodeFromString<PlaybackPushRequest>(request.body.toByteArray().decodeToString())
            return respondJson(
                Json.encodeToString(
                    PlaybackPushResponse(
                        cursor = 1L,
                        accepted =
                            push.items.map {
                                PlaybackAcceptedEntity(it.entity.entityKey, it.entity.mutationId, cursor = 1L)
                            },
                    ),
                ),
            )
        }
    }
}
