package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushToFront
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.yfuse.backend.BackendAccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 我的's pages: the settings root, and every page opened over it. The configuration of
 * [ProfileComponent.pages], saved by name — see [ProfilePageSerializer].
 */
@Serializable(with = ProfilePageSerializer::class)
enum class ProfilePage {
    Root,
    Personal,
    ViewingStatistics,
    Family,
    Sync,
    Handoff,
    Trakt,
    Account,
    AccountSessions,
    Playback,
    AdvancedPlayback,
    Danmaku,
    WatchTogether,
    Appearance,
    GlassMaterial,
    DataAndDiagnostics,
    Downloads,
    Splash,
}

/** Cloud pages cannot be entered or restored by a build that omits the project backend. */
internal fun ProfilePage.availableInCurrentBuild(backend: BackendAccess = BackendAccess.Default): Boolean =
    backend.enabled ||
        this !in
        setOf(
            ProfilePage.Account,
            ProfilePage.AccountSessions,
            ProfilePage.Handoff,
            ProfilePage.WatchTogether,
            ProfilePage.Trakt,
        )

/**
 * A page by its name, as the stack kept it before it moved onto Decompose. A name this build does
 * not have — a page a later build removed — reads back as [ProfilePage.Root], which a restored
 * stack starts from anyway (see [restoredProfilePages]): that one page is dropped, instead of
 * failing the restore of every page above it.
 */
internal object ProfilePageSerializer : KSerializer<ProfilePage> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.yfuse.feature.profile.ProfilePage", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ProfilePage,
    ) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ProfilePage {
        val name = decoder.decodeString()
        return ProfilePage.entries.firstOrNull { it.name == name } ?: ProfilePage.Root
    }
}

/** A saved stack made whole: the root at the bottom, then each page it names once, in order. */
internal fun restoredProfilePages(
    saved: List<ProfilePage>,
    backend: BackendAccess = BackendAccess.Default,
): List<ProfilePage> =
    listOf(ProfilePage.Root) + saved.filter { it != ProfilePage.Root && it.availableInCurrentBuild(backend) }.distinct()

/**
 * 我的's page stack, and the rules for moving through it. It lives in [componentContext] — the
 * component that shows the pages — so a configuration change keeps it and process death brings it
 * back.
 */
internal class ProfilePages<T : Any>(
    componentContext: ComponentContext,
    private val backend: BackendAccess = BackendAccess.Default,
    childFactory: (ProfilePage, ComponentContext) -> T,
) {
    private val navigation = StackNavigation<ProfilePage>()

    val stack: Value<ChildStack<ProfilePage, T>> =
        componentContext.childStack(
            source = navigation,
            initialStack = { listOf(ProfilePage.Root) },
            saveStack = { pages -> SerializableContainer(pages, SavedPages) },
            restoreStack = { saved -> saved.consume(SavedPages)?.let { restoredProfilePages(it, backend) } },
            key = PROFILE_PAGES_STATE_KEY,
            // The Compose shell owns system back, as it does for every tab's own stack.
            handleBackButton = false,
            childFactory = childFactory,
        )

    /**
     * [page] over whatever is showing. A page already open further down comes to the front rather
     * than opening a second time — Decompose refuses a stack holding one page twice, and a second
     * tap during the push would otherwise build one. The root is always at the bottom already.
     */
    fun open(page: ProfilePage) {
        if (page == ProfilePage.Root || !page.availableInCurrentBuild(backend)) return
        navigation.pushToFront(page)
    }

    /** Back from the page in front to the one under it; at the root there is nowhere to go. */
    fun close() = navigation.pop()

    /** 下载与离线库 asked for from outside 我的: it alone over the root, whatever was open before. */
    fun openDownloads() = navigation.replaceAll(ProfilePage.Root, ProfilePage.Downloads)

    /** 一起看 can no longer be used: its page closes, if it is the one in front. */
    fun closeWatchTogether() {
        if (stack.value.active.configuration == ProfilePage.WatchTogether) navigation.pop()
    }
}

private val SavedPages = ListSerializer(ProfilePage.serializer())

private const val PROFILE_PAGES_STATE_KEY = "profile-pages"
