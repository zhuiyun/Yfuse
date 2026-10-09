package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DecomposeSettings
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import com.yfuse.backend.BackendAccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalDecomposeApi::class)
class ProfilePagesTest {
    private var decomposeSettings = DecomposeSettings.settings

    // A host test has no platform main thread for Decompose to check against; the stack is only
    // ever driven from the test's own thread.
    @BeforeTest
    fun setUp() {
        decomposeSettings = DecomposeSettings.settings
        DecomposeSettings.settings = decomposeSettings.copy(mainThreadCheckEnabled = false)
    }

    @AfterTest
    fun tearDown() {
        DecomposeSettings.settings = decomposeSettings
    }

    @Test
    fun the_stack_starts_at_the_settings_root() {
        assertEquals(listOf(ProfilePage.Root), pages().shown())
    }

    @Test
    fun a_page_opens_over_the_one_in_front_and_back_returns_to_it() {
        val pages = pages()

        pages.open(ProfilePage.Account)
        pages.open(ProfilePage.AccountSessions)
        assertEquals(listOf(ProfilePage.Root, ProfilePage.Account, ProfilePage.AccountSessions), pages.shown())

        pages.close()
        assertEquals(listOf(ProfilePage.Root, ProfilePage.Account), pages.shown())
        pages.close()
        assertEquals(listOf(ProfilePage.Root), pages.shown())
    }

    @Test
    fun back_at_the_root_stays_at_the_root() {
        val pages = pages()

        pages.close()

        assertEquals(listOf(ProfilePage.Root), pages.shown())
    }

    @Test
    fun a_page_already_open_comes_to_the_front_instead_of_opening_twice() {
        val pages = pages()

        pages.open(ProfilePage.Appearance)
        pages.open(ProfilePage.Appearance)
        assertEquals(listOf(ProfilePage.Root, ProfilePage.Appearance), pages.shown())

        pages.open(ProfilePage.GlassMaterial)
        pages.open(ProfilePage.Appearance)
        assertEquals(listOf(ProfilePage.Root, ProfilePage.GlassMaterial, ProfilePage.Appearance), pages.shown())
    }

    @Test
    fun the_root_is_never_opened_over_a_page() {
        val pages = pages()
        pages.open(ProfilePage.Playback)

        pages.open(ProfilePage.Root)

        assertEquals(listOf(ProfilePage.Root, ProfilePage.Playback), pages.shown())
    }

    @Test
    fun the_downloads_deep_link_shows_downloads_alone_over_the_root() {
        val pages = pages()

        pages.openDownloads()
        assertEquals(listOf(ProfilePage.Root, ProfilePage.Downloads), pages.shown())

        pages.close()
        pages.open(ProfilePage.Appearance)
        pages.open(ProfilePage.Splash)
        pages.openDownloads()
        assertEquals(listOf(ProfilePage.Root, ProfilePage.Downloads), pages.shown())
    }

    @Test
    fun an_unavailable_watch_together_page_closes_only_when_it_is_in_front() {
        val pages = pages()
        pages.open(ProfilePage.WatchTogether)
        pages.open(ProfilePage.AccountSessions)

        // Under another page it waits; it is 我的 in front that has to stop offering it.
        pages.closeWatchTogether()
        assertEquals(
            listOf(ProfilePage.Root, ProfilePage.WatchTogether, ProfilePage.AccountSessions),
            pages.shown(),
        )

        pages.close()
        pages.closeWatchTogether()
        assertEquals(listOf(ProfilePage.Root), pages.shown())

        pages.open(ProfilePage.Account)
        pages.closeWatchTogether()
        assertEquals(listOf(ProfilePage.Root, ProfilePage.Account), pages.shown())
    }

    @Test
    fun a_page_is_released_when_it_closes() {
        val lifecycles = mutableMapOf<ProfilePage, Lifecycle>()
        val lifecycle = LifecycleRegistry().apply { resume() }
        val pages =
            ProfilePages(
                DefaultComponentContext(lifecycle),
                backend = BackendAccess(enabled = true),
            ) { page: ProfilePage, context: ComponentContext ->
                lifecycles[page] = context.lifecycle
                page
            }

        pages.open(ProfilePage.Trakt)
        assertEquals(Lifecycle.State.RESUMED, lifecycles.getValue(ProfilePage.Trakt).state)

        pages.close()
        assertEquals(Lifecycle.State.DESTROYED, lifecycles.getValue(ProfilePage.Trakt).state)
        assertEquals(Lifecycle.State.RESUMED, lifecycles.getValue(ProfilePage.Root).state)
    }

    @Test
    fun the_stack_comes_back_after_process_death() {
        val stateKeeper = StateKeeperDispatcher()
        val pages = pages(stateKeeper)
        pages.open(ProfilePage.Appearance)
        pages.open(ProfilePage.GlassMaterial)

        val restored = pages(StateKeeperDispatcher(stateKeeper.save().throughProcessDeath()))

        assertEquals(listOf(ProfilePage.Root, ProfilePage.Appearance, ProfilePage.GlassMaterial), restored.shown())
    }

    @Test
    fun a_saved_page_this_build_does_not_have_is_dropped_and_the_rest_restored() {
        val saved =
            Json.decodeFromString(
                ListSerializer(ProfilePage.serializer()),
                """["Root","RemovedPage","Appearance","Splash"]""",
            )

        assertEquals(
            listOf(ProfilePage.Root, ProfilePage.Appearance, ProfilePage.Splash),
            restoredProfilePages(saved),
        )
    }

    @Test
    fun a_restored_stack_keeps_the_root_at_the_bottom_and_each_page_once() {
        val saved = listOf(ProfilePage.Downloads, ProfilePage.Root, ProfilePage.Downloads, ProfilePage.Danmaku)

        assertEquals(
            listOf(ProfilePage.Root, ProfilePage.Downloads, ProfilePage.Danmaku),
            restoredProfilePages(saved),
        )
        assertEquals(listOf(ProfilePage.Root), restoredProfilePages(emptyList()))
    }

    @Test
    fun a_backend_disabled_build_cannot_open_cloud_pages_but_keeps_local_pages() {
        val created = mutableListOf<ProfilePage>()
        val pages = pages(backend = BackendAccess(enabled = false), onCreate = created::add)
        val cloudPages =
            listOf(
                ProfilePage.Account,
                ProfilePage.AccountSessions,
                ProfilePage.Handoff,
                ProfilePage.WatchTogether,
                ProfilePage.Trakt,
            )

        cloudPages.forEach(pages::open)
        assertEquals(listOf(ProfilePage.Root), pages.shown())
        assertEquals(listOf(ProfilePage.Root), created)

        pages.open(ProfilePage.Personal)
        pages.open(ProfilePage.Family)
        pages.open(ProfilePage.Sync)
        assertEquals(
            listOf(ProfilePage.Root, ProfilePage.Personal, ProfilePage.Family, ProfilePage.Sync),
            pages.shown(),
        )
        pages.openDownloads()
        assertEquals(listOf(ProfilePage.Root, ProfilePage.Downloads), pages.shown())
    }

    @Test
    fun restoring_into_a_backend_disabled_build_never_constructs_cloud_pages() {
        val stateKeeper = StateKeeperDispatcher()
        val enabled = pages(stateKeeper)
        enabled.open(ProfilePage.Account)
        enabled.open(ProfilePage.AccountSessions)
        enabled.open(ProfilePage.Personal)
        enabled.open(ProfilePage.Handoff)
        enabled.open(ProfilePage.WatchTogether)
        enabled.open(ProfilePage.Trakt)
        enabled.open(ProfilePage.Sync)
        val created = mutableListOf<ProfilePage>()

        val restored =
            pages(
                StateKeeperDispatcher(stateKeeper.save().throughProcessDeath()),
                backend = BackendAccess(enabled = false),
                onCreate = created::add,
            )

        val localPages = listOf(ProfilePage.Root, ProfilePage.Personal, ProfilePage.Sync)
        assertEquals(localPages, restored.shown())
        assertEquals(localPages.toSet(), created.toSet())
    }

    private fun pages(
        stateKeeper: StateKeeperDispatcher = StateKeeperDispatcher(),
        backend: BackendAccess = BackendAccess(enabled = true),
        onCreate: (ProfilePage) -> Unit = {},
    ): ProfilePages<ProfilePage> =
        ProfilePages(DefaultComponentContext(LifecycleRegistry(), stateKeeper), backend = backend) { page, _ ->
            onCreate(page)
            page
        }

    private fun ProfilePages<*>.shown(): List<ProfilePage> = stack.value.items.map { it.configuration }

    /** What survives process death is the saved state's bytes, not the objects that were saved. */
    private fun SerializableContainer.throughProcessDeath(): SerializableContainer =
        Json.decodeFromString(
            SerializableContainer.serializer(),
            Json.encodeToString(SerializableContainer.serializer(), this),
        )
}
