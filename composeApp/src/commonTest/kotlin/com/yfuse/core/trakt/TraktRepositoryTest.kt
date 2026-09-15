package com.yfuse.core.trakt

import com.yfuse.core.security.TestSecureStore
import com.yfuse.watch.protocol.TraktAuthChallenge
import com.yfuse.watch.protocol.TraktAuthPoll
import com.yfuse.watch.protocol.TraktAuthStatus
import com.yfuse.watch.protocol.TraktConfiguration
import com.yfuse.watch.protocol.TraktRefreshRequest
import com.yfuse.watch.protocol.TraktToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktRepositoryTest {
    @Test
    fun reportingRequiresExplicitOptInAndRespectsWriteInterval() =
        runTest {
            val fixture = fixture()
            fixture.connect()
            fixture.record(TraktPlaybackAction.Start)
            runCurrent()
            assertTrue(fixture.api.writes.isEmpty())
            fixture.repository.setScrobbling(true)
            fixture.record(TraktPlaybackAction.Start)
            runCurrent()
            fixture.record(TraktPlaybackAction.Pause)
            runCurrent()
            assertEquals(listOf(TraktPlaybackAction.Start), fixture.api.writes)
            advanceTimeBy(1_000)
            fixture.repository.retryPending()
            runCurrent()
            assertEquals(listOf(TraktPlaybackAction.Start, TraktPlaybackAction.Pause), fixture.api.writes)
            fixture.repository.close()
        }

    @Test
    fun retryAfterCannotBeBypassedAndCompletedOfflineEventIsRetained() =
        runTest {
            val fixture = fixture()
            fixture.connect()
            fixture.repository.setScrobbling(true)
            fixture.api.failure = TraktApiException(429, 30)
            fixture.record(TraktPlaybackAction.Stop)
            runCurrent()
            assertEquals(1, fixture.api.writes.size)
            advanceTimeBy(10_000)
            fixture.repository.retryPending()
            runCurrent()
            assertEquals(1, fixture.api.writes.size)
            advanceTimeBy(16 * 60_000)
            runCurrent()
            assertEquals(1, fixture.repository.state.value.pending)
            fixture.api.failure = null
            advanceTimeBy(901_000)
            runCurrent()
            assertEquals(0, fixture.repository.state.value.pending)
            fixture.repository.close()
        }

    @Test
    fun profileSwitchAndReturnCannotRecordOldPlayerCallbacks() =
        runTest {
            val fixture = fixture()
            fixture.connect()
            fixture.repository.setScrobbling(true)
            val captured = fixture.repository.capturePlaybackOwner()
            fixture.owner.value = "account:child"
            runCurrent()
            assertFalse(fixture.repository.state.value.connected)
            fixture.owner.value = "account:adult"
            runCurrent()
            assertTrue(fixture.repository.state.value.connected)
            fixture.repository.recordPlayback(
                media,
                "old-player",
                TraktPlaybackAction.Stop,
                95_000,
                100_000,
                expectedOwner = captured,
            )
            runCurrent()
            assertTrue(fixture.api.writes.isEmpty())
            fixture.repository.close()
        }

    @Test
    fun expiredTokenRotatesOnceBeforeImportAndStoresReplacement() =
        runTest {
            val fixture = fixture()
            fixture.auth.token = token.copy(createdAt = 1, expiresIn = 60)
            fixture.connect()
            fixture.repository.importHistory()
            runCurrent()
            assertEquals(1, fixture.auth.refreshes.size)
            assertEquals("rotated-access", fixture.api.lastToken)
            fixture.repository.importHistory()
            runCurrent()
            assertEquals(1, fixture.auth.refreshes.size)
            fixture.repository.close()
        }

    @Test
    fun failedImportContinuesAtUnfinishedPageAndDisconnectDropsQueue() =
        runTest {
            val fixture = fixture()
            fixture.connect()
            fixture.api.failPage = 2
            fixture.api.morePages = true
            fixture.repository.importWatchlist()
            runCurrent()
            assertEquals(listOf(1, 2), fixture.api.pages)
            fixture.api.failPage = null
            fixture.repository.importWatchlist()
            runCurrent()
            assertEquals(listOf(1, 2, 2), fixture.api.pages)
            fixture.repository.setScrobbling(true)
            fixture.api.failure = TraktApiException(503)
            fixture.record(TraktPlaybackAction.Stop)
            runCurrent()
            assertEquals(1, fixture.repository.state.value.pending)
            fixture.repository.disconnect()
            runCurrent()
            assertFalse(fixture.repository.state.value.connected)
            assertEquals(0, fixture.repository.state.value.pending)
            fixture.repository.close()
        }

    private fun TestScope.fixture(): Fixture {
        val api = FakeApi()
        val auth = FakeAuth { BASE_TIME + testScheduler.currentTime }
        val owner = MutableStateFlow<String?>("account:adult")
        val sink =
            object : TraktImportSink {
                override suspend fun importWatchlist(item: TraktListItem) = true

                override suspend fun importHistory(item: TraktListItem) = true
            }
        val repository =
            TraktRepository(api, auth, TestSecureStore(), owner, sink, backgroundScope) {
                BASE_TIME +
                    testScheduler.currentTime
            }
        repository.start()
        runCurrent()
        return Fixture(repository, api, auth, owner, this)
    }

    private class Fixture(
        val repository: TraktRepository,
        val api: FakeApi,
        val auth: FakeAuth,
        val owner: MutableStateFlow<String?>,
        val scope: TestScope,
    ) {
        fun connect() {
            repository.connect(true)
            scope.runCurrent()
            scope.advanceTimeBy(5_000)
            scope.runCurrent()
            assertTrue(repository.state.value.connected)
        }

        fun record(action: TraktPlaybackAction) =
            repository.recordPlayback(media, "playback-1", action, 95_000, 100_000)
    }

    private class FakeAuth(
        private val now: () -> Long,
    ) : TraktAuthApi {
        var token = TraktRepositoryTest.token
        val refreshes = mutableListOf<TraktRefreshRequest>()

        override suspend fun configuration() = TraktConfiguration("public-id", true, true)

        override suspend fun begin(device: Boolean) =
            TraktAuthChallenge(
                "request-0000000001",
                "https://auth.trakt.tv/activate",
                "ABCD",
                now() + 600_000,
            )

        override suspend fun poll(id: String) = TraktAuthPoll(TraktAuthStatus.Connected, token)

        override suspend fun cancel(id: String) {}

        override suspend fun refresh(request: TraktRefreshRequest): TraktToken {
            refreshes += request
            return TraktRepositoryTest.token.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh")
        }

        override suspend fun revoke(accessToken: String) {}
    }

    private class FakeApi : TraktApi {
        val writes = mutableListOf<TraktPlaybackAction>()
        val pages = mutableListOf<Int>()
        var failure: Exception? = null
        var failPage: Int? = null
        var morePages = false
        var lastToken: String? = null

        override suspend fun history(
            clientId: String,
            token: String,
            page: Int,
            endAt: String,
        ): TraktPage {
            lastToken =
                token
            return TraktPage(emptyList(), false)
        }

        override suspend fun watchlist(
            clientId: String,
            token: String,
            page: Int,
        ): TraktPage {
            pages += page
            if (page == failPage) error("network unavailable")
            return TraktPage(emptyList(), morePages && page == 1)
        }

        override suspend fun scrobble(
            clientId: String,
            token: String,
            media: TraktPlaybackMedia,
            action: TraktPlaybackAction,
            progress: Float,
        ) {
            writes +=
                action
            failure?.let { throw it }
        }
    }

    companion object {
        private const val BASE_TIME = 1_700_000_000_000L
        private val token = TraktToken("access", "refresh", 604800, BASE_TIME / 1000)
        private val media = TraktPlaybackMedia("movie", TraktIds(tmdb = 603))
    }
}
