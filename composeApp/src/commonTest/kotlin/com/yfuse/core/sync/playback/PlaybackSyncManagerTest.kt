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
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.watch.protocol.PlaybackMissingEntity
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun an_evicted_cloud_entity_is_retried_as_a_cas_creation() =
        runTest {
            val bases = mutableListOf<Long>()
            val fixture =
                fixture(cloudHandler = { request ->
                    if (request.method == HttpMethod.Get) {
                        successfulPlaybackResponse(request)
                    } else {
                        val item =
                            Json
                                .decodeFromString<PlaybackPushRequest>(
                                    request.body.toByteArray().decodeToString(),
                                ).items
                                .single()
                        bases += item.baseCursor
                        val response =
                            if (bases.size == 2) {
                                PlaybackPushResponse(
                                    cursor = 2L,
                                    missing =
                                        listOf(
                                            PlaybackMissingEntity(
                                                item.entity.entityKey,
                                                item.entity.mutationId,
                                                item.baseCursor,
                                            ),
                                        ),
                                )
                            } else {
                                PlaybackPushResponse(
                                    cursor = bases.size.toLong(),
                                    accepted =
                                        listOf(
                                            PlaybackAcceptedEntity(
                                                item.entity.entityKey,
                                                item.entity.mutationId,
                                                bases.size.toLong(),
                                            ),
                                        ),
                                )
                            }
                        respondJson(Json.encodeToString(response))
                    }
                })
            try {
                fixture.manager.start()
                runCurrent()
                fixture.recordStop(20_000L)
                advanceTimeBy(5_000L)
                runCurrent()

                assertEquals(listOf(0L, 1L, 0L), bases)
                assertTrue(fixture.store.pending().isEmpty())
                assertEquals(20_000L, fixture.manager.resumePositionMs(MEDIA_KEY))
                assertNull(fixture.manager.state.value.error)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun an_empty_legacy_push_response_keeps_pending_data_and_reports_failure_with_backoff() =
        runTest {
            var pushes = 0
            val fixture =
                fixture(cloudHandler = { request ->
                    if (request.method == HttpMethod.Get) {
                        successfulPlaybackResponse(request)
                    } else {
                        pushes++
                        respondJson("""{"cursor":10,"accepted":[],"conflicts":[]}""")
                    }
                })
            try {
                fixture.manager.start()
                runCurrent()
                assertEquals(1, pushes)
                assertEquals(1, fixture.store.pending().size)
                assertNotNull(fixture.manager.state.value.error)
                assertNull(fixture.manager.state.value.lastSyncedAtEpochMs)
                advanceTimeBy(29_999L)
                runCurrent()
                assertEquals(1, pushes)
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(2, pushes)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun close_cancels_a_queued_retry_without_discarding_pending_progress() =
        runTest {
            var pushes = 0
            val fixture =
                fixture(cloudHandler = { request ->
                    if (request.method == HttpMethod.Get) {
                        successfulPlaybackResponse(request)
                    } else {
                        pushes++
                        respondJson("""{"cursor":10,"accepted":[],"conflicts":[]}""")
                    }
                })
            try {
                fixture.manager.start()
                runCurrent()
                assertEquals(1, pushes)
                assertNotNull(fixture.manager.state.value.error)
                val pending = fixture.store.pending()
                assertEquals(1, pending.size)

                // The preceding backoff test proves this response retries at 30 seconds.
                // Close just before that deadline while keeping the HTTP clients open.
                advanceTimeBy(29_999L)
                runCurrent()
                assertEquals(1, pushes)
                fixture.manager.close()
                advanceTimeBy(60_000L)
                runCurrent()

                assertEquals(1, pushes, "Closing the manager must cancel its already queued cloud retry")
                assertEquals(pending, fixture.store.pending())
                assertEquals(12_000L, fixture.persistedPositionMs())
                assertFalse(fixture.manager.state.value.syncing)
            } finally {
                fixture.close()
            }
        }

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
    fun leaving_the_foreground_writes_coalesced_progress_to_storage() =
        runTest {
            val fixture = fixture()
            try {
                fixture.manager.recordPlayback(
                    mediaKey = MEDIA_KEY,
                    aliases = emptyList(),
                    positionMs = 30_000L,
                    durationMs = 100_000L,
                    sessionId = "playing-session",
                    serverId = null,
                    serverItemId = null,
                    trigger = PlaybackSyncTrigger.Periodic,
                )
                assertEquals(30_000L, fixture.manager.resumePositionMs(MEDIA_KEY))
                assertEquals(12_000L, fixture.persistedPositionMs())

                fixture.manager.setAppForeground(false)
                runCurrent()

                assertEquals(30_000L, fixture.persistedPositionMs())
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

    @Test
    fun an_unreachable_server_is_asked_once_per_backoff_not_once_per_queued_title() =
        runTest {
            var embyRequests = 0
            val fixture =
                fixture(
                    servers = listOf(SERVER),
                    embyHandler = {
                        embyRequests++
                        throw SocketTimeoutException("server did not answer")
                    },
                )
            try {
                // Tonight's diagnostic: ~20 titles queued for one server that only timed out.
                repeat(5) { index ->
                    val document =
                        fixture.store
                            .updatePlayback(
                                mediaKey = "tmdb:${100 + index}",
                                aliases = emptyList(),
                                positionMs = 30_000L,
                                durationMs = 100_000L,
                                played = false,
                                sessionId = "other-device-$index",
                                serverId = null,
                                serverItemId = null,
                                mutationKind = PlaybackMutationKind.AutoProgress,
                                trigger = PlaybackSyncTrigger.Stop,
                            ).document
                    fixture.store.enqueueServerApply(document, listOf(SERVER.id))
                }
                fixture.manager.start()
                runCurrent()

                // One title found the server down; the other four wait instead of each paying
                // for their own timeout.
                assertEquals(1, embyRequests)
                assertEquals(5, fixture.store.serverApplyCount())

                advanceTimeBy(10_000L)
                runCurrent()
                assertEquals(1, embyRequests)

                // After the first 15 s backoff one title probes again, and the next wait doubles.
                advanceTimeBy(6_000L)
                runCurrent()
                assertEquals(2, embyRequests)
                advanceTimeBy(20_000L)
                runCurrent()
                assertEquals(2, embyRequests)
                assertEquals(5, fixture.store.serverApplyCount())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun startup_pull_continues_after_its_eight_page_budget_until_the_final_page() =
        runTest {
            var pages = 0
            lateinit var fixture: Fixture
            fixture =
                fixture(cloudHandler = { request ->
                    if (request.method == HttpMethod.Get) {
                        val cursor = (++pages).toLong()
                        val doc =
                            PlaybackSyncDocument(
                                state =
                                    PlaybackStateRecord(
                                        mediaKey = "tmdb:$cursor",
                                        positionMs = cursor * 1000L,
                                        deviceId = fixture.store.deviceId,
                                        lastPlayedAtEpochMs = 1000000L + cursor,
                                    ),
                            )
                        val encrypted =
                            assertNotNull(
                                fixture.cipher.encrypt(doc, "remote-page-$cursor"),
                            ).copy(cursor = cursor)
                        respondJson(
                            Json.encodeToString(
                                PlaybackDeltaResponse(
                                    cursor,
                                    listOf(encrypted),
                                    hasMore =
                                        pages < 10,
                                ),
                            ),
                        )
                    } else {
                        successfulPlaybackResponse(request)
                    }
                })
            try {
                fixture.manager.start()
                runCurrent()
                assertEquals(8, pages)
                advanceTimeBy(20000L)
                runCurrent()
                assertEquals(10, pages)
                assertEquals(
                    10000L,
                    fixture.store
                        .find("tmdb:10")
                        ?.document
                        ?.state
                        ?.positionMs,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun switching_accounts_while_sync_is_disabled_isolates_progress_before_reenabling() =
        runTest {
            val enabled = MutableStateFlow(false)
            val fixture = fixture(withPersonal = true, enabled = enabled)
            try {
                fixture.manager.start()
                fixture.recordStop(12000L)
                fixture.account.logout().getOrThrow()
                fixture.account.login("viewer_bb", "correct horse battery".toCharArray()).getOrThrow()
                runCurrent()
                assertNull(fixture.manager.startPositionMs(MEDIA_KEY))
                fixture.manager.recordPlayback(
                    MEDIA_KEY,
                    emptyList(),
                    36000L,
                    100000L,
                    "b-new-session",
                    null,
                    null,
                    PlaybackSyncTrigger.Stop,
                )
                enabled.value = true
                runCurrent()
                assertEquals(36000L, fixture.manager.startPositionMs(MEDIA_KEY))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun returning_to_the_same_account_in_one_process_pulls_its_cleared_partition_again() =
        runTest {
            val pulls = mutableMapOf<String, Int>()
            lateinit var fixture: Fixture
            fixture =
                fixture(withPersonal = true, cloudHandler = { request ->
                    if (request.method == HttpMethod.Get) {
                        val id = fixture.cipher.currentUserId()!!
                        pulls[id] = pulls.getOrDefault(id, 0) + 1
                        val doc =
                            PlaybackSyncDocument(
                                state =
                                    PlaybackStateRecord(
                                        mediaKey = MEDIA_KEY,
                                        positionMs = 45000L,
                                        deviceId = fixture.store.deviceId,
                                        lastPlayedAtEpochMs = 100000L,
                                    ),
                            )
                        val encrypted = assertNotNull(fixture.cipher.encrypt(doc, "remote-$id")).copy(cursor = 1L)
                        respondJson(
                            Json.encodeToString(PlaybackDeltaResponse(cursor = 1L, changes = listOf(encrypted))),
                        )
                    } else {
                        successfulPlaybackResponse(request)
                    }
                })
            try {
                fixture.manager.start()
                runCurrent()
                fixture.account.logout().getOrThrow()
                runCurrent()
                fixture.account.login("viewer_bb", "correct horse battery".toCharArray()).getOrThrow()
                runDeferredAccountPull(fixture)
                fixture.account.logout().getOrThrow()
                runCurrent()
                fixture.account.login("viewer_01", "correct horse battery".toCharArray()).getOrThrow()
                runDeferredAccountPull(fixture)
                assertEquals(
                    2,
                    pulls["account"],
                    fixture.manager.state.value
                        .toString(),
                )
                assertEquals(45000L, fixture.manager.startPositionMs(MEDIA_KEY))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun personal_owner_published_before_account_identity_cannot_rebind_the_old_account() =
        runTest {
            var pulls = 0
            val fixture =
                fixture(withPersonal = true, cloudHandler = { request ->
                    if (request.method == HttpMethod.Get) pulls++
                    successfulPlaybackResponse(request)
                })
            try {
                fixture.manager.start()
                runCurrent()
                assertEquals(1, pulls)
                assertNotNull(fixture.personal).bindAccount("other")
                runCurrent()
                assertEquals("other", fixture.settings.getStringOrNull("playback.cross_platform.account_user.v1"))
                assertEquals(1, pulls, "The account state still names A and cannot bind into B's scope")
                fixture.account.login("viewer_bb", "correct horse battery".toCharArray()).getOrThrow()
                runDeferredAccountPull(fixture)
                assertTrue(
                    pulls > 1,
                    "The stable B identity must trigger a new pull even when sessionAvailable stayed true",
                )
            } finally {
                fixture.close()
            }
        }

    private fun TestScope.runDeferredAccountPull(fixture: Fixture) {
        assertTrue(fixture.cipher.hasKey())
        // Account operations run on a real, detached dispatcher. While login is suspended,
        // runTest can advance through several key-unavailable retries before the key lands.
        // Once login returns, allow the maximum configured backoff, without manual refresh.
        advanceTimeBy(playbackCloudRetryBackoffMs(6))
        runCurrent()
    }

    private suspend fun TestScope.fixture(
        tokenProvider: suspend () -> String? = { "access" },
        refreshProvider: suspend () -> String? = { "refreshed" },
        cloudHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { successfulPlaybackResponse(it) },
        servers: List<SavedServer> = emptyList(),
        withPersonal: Boolean = false,
        enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        embyHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { error("Unexpected Emby request") },
    ): Fixture {
        val settings = MapSettings()
        val secureStore = TestSecureStore()
        val personal =
            if (withPersonal) {
                com.yfuse.core.personal
                    .PersonalLibraryRepository(settings)
            } else {
                null
            }
        val registry = ServerRegistry(settings, TestSecureStore(), personal = personal)
        val crypto = VaultCrypto()
        val embyClient =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler(embyHandler)
                    },
                ),
            )
        val emby = EmbyRepository(embyClient)
        val tokens = AccountAccessTokenSource(ACCOUNT_ORIGIN)
        val accountClient =
            createAccountClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/api/v1/auth/prelogin") {
                        // A service from before account protocol 2.
                        respond("", HttpStatusCode.NotFound)
                    } else if (request.url.encodedPath == "/api/v1/account/sync") {
                        respondJson("""{"version":0}""")
                    } else if (request.url.encodedPath == "/api/v1/auth/logout") {
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        val id =
                            if (request.url.encodedPath == "/api/v1/auth/login") {
                                Json
                                    .decodeFromString<com.yfuse.core.account.LoginRequest>(
                                        request.body.toByteArray().decodeToString(),
                                    ).username
                                    .let { if (it == "viewer_01") "account" else "other" }
                            } else {
                                check(request.url.encodedPath == "/api/v1/auth/register")
                                "account"
                            }
                        respondJson(
                            Json.encodeToString(
                                AuthResponse(
                                    user = AccountUser(id, "viewer_01", "viewer", 1, 0L, 0L),
                                    accessToken = "access",
                                    accessExpiresAtEpochMs = Long.MAX_VALUE,
                                    refreshToken = "refresh",
                                    refreshExpiresAtEpochMs = Long.MAX_VALUE,
                                ),
                            ),
                        )
                    }
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
                personal = personal,
            )
        account.register("viewer_01", "correct horse battery".toCharArray()).getOrThrow()
        servers.forEach(registry::addOrUpdate)
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
        val store = PlaybackSyncStore(settings, { testScheduler.currentTime }, personal)
        val cipher = PlaybackVaultCipher(account, secureStore, crypto)
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
                cipher = cipher,
                personal = personal,
                progressSyncEnabled = enabled,
                accessTokens = tokens,
                repo = emby,
                registry = registry,
                nowEpochMs = { testScheduler.currentTime },
                scope = syncScope,
            )
        return Fixture(
            manager,
            store,
            account,
            tokens,
            settings,
            syncScope,
            listOf(accountClient, cloudClient, embyClient),
            cipher,
            personal,
        )
    }

    private class Fixture(
        val manager: PlaybackSyncManager,
        val store: PlaybackSyncStore,
        val account: AccountRepository,
        val tokens: AccountAccessTokenSource,
        val settings: MapSettings,
        private val scope: CoroutineScope,
        private val clients: List<HttpClient>,
        val cipher: PlaybackVaultCipher,
        val personal: com.yfuse.core.personal.PersonalLibraryRepository?,
    ) {
        /** What a process started now would resume from: storage, not this store's memory. */
        fun persistedPositionMs(): Long? =
            PlaybackSyncStore(settings)
                .find(MEDIA_KEY)
                ?.document
                ?.state
                ?.positionMs

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
            account.close()
            clients.forEach(HttpClient::close)
        }
    }

    private companion object {
        const val ACCOUNT_ORIGIN = "https://account.example.test"
        const val MEDIA_KEY = "tmdb:1"
        val SERVER = SavedServer("server", "https://library.example", "家庭影院", "user", "用户", "token")

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
