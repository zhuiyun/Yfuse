package com.yfuse.feature.trakt

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.trakt.TraktImportSink
import com.yfuse.core.trakt.TraktListItem
import com.yfuse.core.trakt.TraktRepository
import com.yfuse.core.trakt.TraktRepositoryTest
import com.yfuse.core.trakt.TraktUiState
import com.yfuse.watch.protocol.TraktConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktSettingsStoreTest {
    // One clock for the store, the repository's authorization and the test that advances both.
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_configuration_is_read_as_the_page_shows_and_again_as_the_account_changes() =
        runTest(scheduler) {
            val fixture = fixture()
            val store = store(fixture.repository)
            assertNull(store.state.trakt.configuration)
            assertFalse(store.state.canConnect)

            store.accept(TraktSettingsIntent.PageShown)
            runCurrent()
            assertEquals(CONFIGURATION, store.state.trakt.configuration)
            assertTrue(store.state.canConnect)

            fixture.owner.value = null
            runCurrent()
            assertFalse(store.state.trakt.signedIn)
            assertNull(store.state.trakt.configuration)

            fixture.owner.value = OWNER
            runCurrent()
            assertEquals(CONFIGURATION, store.state.trakt.configuration)
            store.dispose()
        }

    @Test
    fun a_phone_connects_through_trakts_own_page() =
        runTest(scheduler) {
            val fixture = fixture()
            val store = store(fixture.repository)
            store.accept(TraktSettingsIntent.PageShown)
            runCurrent()

            store.accept(TraktSettingsIntent.Connect)
            runCurrent()
            assertTrue(store.state.trakt.busy)
            assertFalse(store.state.canConnect)
            assertEquals("https://auth.trakt.tv/activate", store.state.verificationPage)

            advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()
            assertTrue(store.state.trakt.connected)
            assertNull(store.state.trakt.challenge)
            assertEquals("Trakt 已连接；播放上报默认关闭", store.state.trakt.message)
            store.dispose()
        }

    @Test
    fun leaving_the_page_ends_an_authorization_under_way() =
        runTest(scheduler) {
            val fixture = fixture()
            val store = store(fixture.repository)
            store.accept(TraktSettingsIntent.PageShown)
            store.accept(TraktSettingsIntent.Connect)
            runCurrent()

            store.accept(TraktSettingsIntent.PageHidden)
            runCurrent()

            assertNull(store.state.trakt.challenge)
            assertFalse(store.state.trakt.busy)
            assertFalse(store.state.trakt.connected)
            assertEquals("Trakt 授权已取消或过期", store.state.trakt.error)
            store.dispose()
        }

    @Test
    fun a_closed_page_ends_an_authorization_under_way() =
        runTest(scheduler) {
            val fixture = fixture()
            val store = store(fixture.repository)
            store.accept(TraktSettingsIntent.PageShown)
            store.accept(TraktSettingsIntent.Connect)
            runCurrent()

            store.dispose()
            runCurrent()

            assertNull(fixture.repository.state.value.challenge)
            assertEquals("Trakt 授权已取消或过期", fixture.repository.state.value.error)
        }

    @Test
    fun imports_reporting_and_disconnecting_go_to_trakt() =
        runTest(scheduler) {
            val fixture = fixture()
            val store = store(fixture.repository)
            store.accept(TraktSettingsIntent.PageShown)
            store.accept(TraktSettingsIntent.Connect)
            runCurrent()
            advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()

            store.accept(TraktSettingsIntent.SetScrobbling(true))
            runCurrent()
            assertTrue(store.state.trakt.scrobbling)

            store.accept(TraktSettingsIntent.ImportWatchlist)
            runCurrent()
            assertEquals(listOf(1), fixture.api.pages)
            assertEquals("已导入 0 项，保留或跳过 0 项", store.state.trakt.message)

            store.accept(TraktSettingsIntent.Disconnect)
            runCurrent()
            assertFalse(store.state.trakt.connected)
            store.dispose()
        }

    @Test
    fun a_television_connects_only_where_its_code_authorization_is_configured() {
        val signedIn =
            TraktUiState(
                signedIn = true,
                configuration = TraktConfiguration("id", oauthAvailable = true, deviceAvailable = false),
            )

        assertTrue(TraktSettingsState(signedIn, television = false).canConnect)
        assertFalse(TraktSettingsState(signedIn, television = true).canConnect)
        assertFalse(TraktSettingsState(signedIn.copy(busy = true)).canConnect)
        assertFalse(TraktSettingsState(signedIn.copy(signedIn = false)).canConnect)
    }

    @Test
    fun only_trakts_own_pages_over_https_are_offered_to_open() {
        assertTrue(trustedTraktPage("https://auth.trakt.tv/activate"))
        assertTrue(trustedTraktPage("https://trakt.tv/activate"))
        assertFalse(trustedTraktPage("http://trakt.tv/activate"))
        assertFalse(trustedTraktPage("https://trakt.tv.example.com/activate"))
        assertFalse(trustedTraktPage("not a page"))
    }

    private fun store(repository: TraktRepository): Store<TraktSettingsIntent, TraktSettingsState, Nothing> =
        TraktSettingsStoreFactory(DefaultStoreFactory(), repository, television = false).create()

    private fun TestScope.fixture(): Fixture {
        val now = { NOW + testScheduler.currentTime }
        val api = TraktRepositoryTest.FakeApi()
        val owner = MutableStateFlow<String?>(OWNER)
        val sink =
            object : TraktImportSink {
                override suspend fun importWatchlist(item: TraktListItem) = true

                override suspend fun importHistory(item: TraktListItem) = true
            }
        val repository =
            TraktRepository(
                api = api,
                auth = TraktRepositoryTest.FakeAuth(now),
                secureStore = TestSecureStore(),
                owner = owner,
                importSink = sink,
                scope = backgroundScope,
                now = now,
            )
        repository.start()
        runCurrent()
        return Fixture(repository, api, owner)
    }

    private class Fixture(
        val repository: TraktRepository,
        val api: TraktRepositoryTest.FakeApi,
        val owner: MutableStateFlow<String?>,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val OWNER = "account:adult"

        /** The authorization's own polling interval, as the fake challenge sets it. */
        const val POLL_INTERVAL_MS = 5_000L
        val CONFIGURATION = TraktConfiguration("public-id", oauthAvailable = true, deviceAvailable = true)
    }
}
