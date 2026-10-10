package com.yfuse.feature.player

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.AccountApi
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.PlaybackCloudApi
import com.yfuse.core.account.PlaybackVaultCipher
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
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.sync.playback.PlaybackSyncStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackRecordDurabilityTest {
    @Test
    fun shortPlaybackIsPersistedAtCloseWhileTheStartRequestIsStillBlocked() =
        runTest {
            val fixture = fixture()
            try {
                val release = CompletableDeferred<Unit>()
                val reporter =
                    PlaybackProgressReporter(
                        listOf(item),
                        WaitingSink(release),
                        scope = this,
                        playbackSync = fixture.manager,
                    )
                reporter.update(PlaybackState(playing = true, positionMs = 0, durationMs = 600_000))
                runCurrent()
                reporter.close(PlaybackState(positionMs = 3_000, durationMs = 600_000))
                assertEquals(3_000L, fixture.store.stateForServerItem("server", "movie")?.positionMs)
                // A restarted process sees the record before the network request returns.
                assertEquals(
                    3_000L,
                    PlaybackSyncStore(fixture.settings, personal = fixture.personal)
                        .stateForServerItem("server", "movie")
                        ?.positionMs,
                )
                release.complete(Unit)
                runCurrent()
                assertEquals(3_000L, fixture.store.stateForServerItem("server", "movie")?.positionMs)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun closeBeforeActorDeliveryUsesTheLatestReboundItem() =
        runTest {
            val fixture = fixture()
            try {
                val reporter =
                    PlaybackProgressReporter(
                        listOf(item),
                        WaitingSink(CompletableDeferred(Unit)),
                        scope = this,
                        playbackSync = fixture.manager,
                    )
                reporter.update(PlaybackState(playing = true, positionMs = 0, durationMs = 600_000))
                val next = item.copy(id = "next", watchKey = "emby:next", matchKeys = listOf("emby:next"))
                reporter.rebind(listOf(next), PlaybackState(playing = true, positionMs = 1_000, durationMs = 600_000))
                reporter.close(PlaybackState(positionMs = 4_000, durationMs = 600_000))
                assertEquals(4_000L, fixture.store.stateForServerItem("server", "next")?.positionMs)
                runCurrent()
                assertEquals(4_000L, fixture.store.stateForServerItem("server", "next")?.positionMs)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun delayedExitCannotWriteIntoAnotherAccount() =
        runTest {
            val fixture = fixture()
            try {
                val reporter =
                    PlaybackProgressReporter(
                        listOf(item),
                        WaitingSink(CompletableDeferred(Unit)),
                        scope = this,
                        playbackSync = fixture.manager,
                    )
                fixture.personal.bindAccount("other")
                reporter.close(PlaybackState(positionMs = 3_000, durationMs = 600_000))
                runCurrent()
                assertNull(fixture.store.stateForServerItem("server", "movie"))
            } finally {
                fixture.client.close()
            }
        }

    private fun TestScope.fixture(): Fixture {
        val settings = MapSettings()
        val secure = TestSecureStore()
        val personal = PersonalLibraryRepository(settings)
        val registry = ServerRegistry(settings, secure, personal = personal)
        val client = HttpClient(MockEngine { error("Local playback must not wait for an HTTP request") })
        val emby = EmbyRepository(client)
        val crypto = VaultCrypto()
        val tokens = AccountAccessTokenSource("https://account.example")
        val account =
            AccountRepository(
                AccountApi(client, "https://account.example"),
                secure,
                crypto,
                registry,
                ThemePreferences(settings),
                UserAgentPreferences(settings),
                WatchTogetherPreferences(settings),
                DanmakuPreferences(settings),
                SkipSegmentPreferences(settings),
                ServerSyncManager(emby, registry, settings),
            )
        val store = PlaybackSyncStore(settings, personal = personal)
        val manager =
            PlaybackSyncManager(
                store,
                PlaybackCloudApi(client, "https://account.example"),
                PlaybackVaultCipher(account, secure, crypto),
                tokens,
                emby,
                registry,
                scope = backgroundScope,
                personal = personal,
            )
        return Fixture(settings, personal, store, manager, client)
    }

    private data class Fixture(
        val settings: MapSettings,
        val personal: PersonalLibraryRepository,
        val store: PlaybackSyncStore,
        val manager: PlaybackSyncManager,
        val client: HttpClient,
    )

    private class WaitingSink(
        private val release: CompletableDeferred<Unit>,
    ) : PlaybackEventSink {
        override suspend fun started(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) {
            release.await()
        }

        override suspend fun progress(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit

        override suspend fun stopped(
            itemId: String,
            sessionId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = Unit
    }

    private val item =
        PlayerMediaItem(
            "movie",
            "direct",
            "hls",
            "电影",
            serverId = "server",
            watchKey = "emby:movie",
        )
}
