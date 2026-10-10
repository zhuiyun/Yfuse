package com.yfuse.di

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import com.yfuse.core.data.DiagnosticPreferences
import com.yfuse.core.data.NoOpCalendarLocalStore
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceFailure
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.player.PlaybackSourcePreload
import com.yfuse.feature.player.PlaybackSourcePreloader
import com.yfuse.feature.player.PlayerMediaItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.BeanDefinition
import org.koin.core.error.DefinitionOverrideException
import org.koin.core.module.Module
import org.koin.core.module.flatten
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * appModule is one Koin module per feature area. Koin reports a missing or doubly bound type only
 * when something asks for it at runtime, so this pins down, without a device, that the feature
 * modules bind exactly what the single module before them did, that none overrides another, and
 * that the graph resolves.
 */
@OptIn(KoinInternalApi::class)
class AppModuleGraphTest {
    private val settings = MapSettings()
    private val feedCache = MapSettings()

    /** Built the way YfuseApp and TvApplication build it, with in-memory platform parameters. */
    private fun graph(): Module =
        appModule(
            settings = settings,
            appVersion = "test",
            diagnosticPreferences = DiagnosticPreferences(settings),
            calendarLocalStore = NoOpCalendarLocalStore,
            feedCacheSettings = { feedCache },
        )

    // ProductSession, and the handoff owner derived from it, collect on Dispatchers.Main.immediate.
    // A test dispatcher nobody advances lets them be built without running what they start.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_feature_modules_bind_exactly_what_the_single_module_did() {
        val root = graph()
        val features = flatten(listOf(root)) - root
        assertTrue(root.mappings.isEmpty(), "appModule binds nothing itself; it includes the feature modules")
        val bound =
            features.flatMap { module ->
                module.mappings.values.map { factory ->
                    describe(factory.beanDefinition, createdAtStart = factory in module.eagerInstances)
                }
            }
        // Sorted, and compared as one text so a failure shows the lines that differ. A binding
        // two modules both declare appears twice here, and fails too.
        assertEquals(BINDINGS_BEFORE_THE_SPLIT.sorted().joinToString("\n"), bound.sorted().joinToString("\n"))
    }

    @Test
    fun the_graph_loads_beside_an_app_shell_module_with_nothing_overridden() {
        // With overrides forbidden, a type and qualifier bound twice - by two feature modules, or by
        // one and the shell's own module - fails the load instead of letting load order pick a winner.
        val app =
            koinApplication {
                allowOverride(false)
                modules(graph(), shellModule())
            }
        try {
            assertEquals(BINDINGS_BEFORE_THE_SPLIT.size + 1, app.koin.instanceRegistry.instances.size)
        } finally {
            app.close()
        }
    }

    @Test
    fun a_type_bound_a_second_time_fails_the_load_instead_of_overriding() {
        // What the test above relies on: Koin refuses the second binding rather than keeping either.
        // Settings, because every graph binds it whatever the feature modules hold.
        assertFailsWith<DefinitionOverrideException> {
            koinApplication {
                allowOverride(false)
                modules(graph(), module { single<Settings> { MapSettings() } })
            }
        }
    }

    @Test
    fun every_binding_commonTest_can_build_resolves_through_its_own_definition() {
        val app =
            koinApplication {
                allowOverride(false)
                modules(graph())
            }
        try {
            val registry = app.koin.instanceRegistry
            val definitions = registry.instances.toMap()
            val ids = definitions.values.map { it.beanDefinition.id }.toSet()
            assertTrue(ids.containsAll(PLATFORM_ONLY), "stale entry in PLATFORM_ONLY: ${PLATFORM_ONLY - ids}")

            app.koin.loadModules(listOf(platformStandIns()), allowOverride = true)
            assertEquals(definitions.keys, registry.instances.keys, "a stand-in may only replace a binding")
            val standIns = registry.instances.filter { (key, factory) -> definitions[key] !== factory }
            val standInIds = standIns.values.map { it.beanDefinition.id }.toSet()
            assertEquals(platformStandIns().mappings.size, standInIds.size)
            assertTrue(standInIds.none { it in PLATFORM_ONLY })

            val failures = mutableListOf<String>()
            var resolvedOwnDefinitions = 0
            registry.instances.values.forEach { factory ->
                val definition = factory.beanDefinition
                if (definition.id in PLATFORM_ONLY) return@forEach
                try {
                    val instance: Any = app.koin.get(definition.primaryType, definition.qualifier)
                    if (!definition.primaryType.isInstance(instance)) {
                        failures += "${definition.id}: resolved to ${instance::class.qualifiedName}"
                    } else if (definition.id !in standInIds) {
                        resolvedOwnDefinitions++
                    }
                } catch (error: Throwable) {
                    val cause = generateSequence(error) { it.cause }.last()
                    failures += "${definition.id}: ${error.message} <- $cause"
                }
            }
            assertEquals(emptyList(), failures)
            assertEquals(ids.size - standInIds.size - PLATFORM_ONLY.size, resolvedOwnDefinitions)
        } finally {
            app.close()
        }
    }

    /**
     * In-memory doubles for bindings built from the platform's own implementations (Android Keystore,
     * OkHttp engines, the WebDAV and SMB readers, MediaCodec), which commonTest cannot construct.
     * Other bindings consume these, so the doubles let those resolve through their own definitions.
     */
    private fun platformStandIns(): Module =
        module {
            single(named("account-http")) { unreachableClient() } onClose { it?.close() }
            single(named("trakt-http")) { unreachableClient() } onClose { it?.close() }
            single { unreachableClient() } onClose { it?.close() }
            single(named("danmaku-http")) { unreachableClient() } onClose { it?.close() }
            single(named("tmdb-http")) { unreachableClient() } onClose { it?.close() }
            single<SecureStore> { TestSecureStore() }
            single {
                ServerRegistry(settings = get(), secureStore = TestSecureStore(), crypto = get(), personal = get())
            }
            single<FileSourceClient> { UnreachableFileSourceClient }
            single<PlaybackDeviceCapabilitiesProvider> {
                PlaybackDeviceCapabilitiesProvider { PlaybackDeviceCapabilities.conservative() }
            }
        }

    /** What YfuseApp and TvApplication each add beside appModule: the platform's playback preloader. */
    private fun shellModule(): Module =
        module {
            single<PlaybackSourcePreloader> {
                object : PlaybackSourcePreloader {
                    override fun preload(item: PlayerMediaItem) = PlaybackSourcePreload {}
                }
            }
        }

    private fun unreachableClient() = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

    private object UnreachableFileSourceClient : FileSourceClient {
        override suspend fun list(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
        ): List<FileSourceEntry> = throw FileSourceException(FileSourceFailure.Unreachable)

        override suspend fun cacheSubtitle(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
            entry: FileSourceEntry,
        ): String = throw FileSourceException(FileSourceFailure.Unreachable)
    }

    private companion object {
        val BeanDefinition<*>.id: String
            get() = primaryType.qualifiedName + (qualifier?.let { "@${it.value}" } ?: "")

        /**
         * One binding as a line: its kind (a definition moved into a `scope {}` block reads Scoped,
         * or Factory), type, qualifier, extra bound types, and whether it closes with the graph or
         * is created at start.
         */
        fun describe(
            definition: BeanDefinition<*>,
            createdAtStart: Boolean,
        ): String =
            buildString {
                append(definition.kind.name).append(' ').append(definition.primaryType.qualifiedName)
                definition.qualifier?.let { append(" named(").append(it.value).append(')') }
                if (definition.secondaryTypes.isNotEmpty()) {
                    append(" binds(")
                    append(definition.secondaryTypes.joinToString("|") { it.qualifiedName.orEmpty() })
                    append(')')
                }
                if (definition.callbacks.onClose != null) append(" onClose")
                if (createdAtStart) append(" createdAtStart")
            }

        /**
         * Built only from the platform's implementations and asked for only by screens and the app
         * shells, never by another binding, so commonTest has nothing to build them with or for.
         */
        val PLATFORM_ONLY =
            setOf(
                "com.yfuse.core.cast.CastManager",
                "com.yfuse.core.filesource.FileSourceLibraryStore",
                "com.yfuse.core.filesource.FileSourceRegistry",
                "com.yfuse.core.offline.OfflineMediaManager",
                "com.yfuse.core.playback.PlaybackMediaProbeService",
                "com.yfuse.core.playback.PlaybackOfflineLicenseManager",
                "com.yfuse.core.playback.PlaybackRuntimeEnvironmentProvider",
                "com.yfuse.core.sync.WatchTogetherClient",
            )

        /**
         * Every binding of the single appModule before it was split by feature, in its declaration
         * order, recorded from that module: kind, type, qualifier, and whether it closes with the graph.
         * A binding added since joins the list beside its neighbours.
         */
        val BINDINGS_BEFORE_THE_SPLIT =
            listOf(
                "Singleton com.russhwolf.settings.Settings",
                "Singleton com.yfuse.backend.BackendAccess",
                "Singleton com.yfuse.backend.BackendDiagnostics",
                "Singleton com.yfuse.backend.CalendarBackendApi",
                "Singleton com.yfuse.backend.QoeBackendApi",
                "Singleton com.yfuse.core.trakt.AccountTraktAuthApi",
                "Singleton io.ktor.client.HttpClient named(account-http) onClose",
                "Singleton io.ktor.client.HttpClient named(trakt-http) onClose",
                "Singleton com.yfuse.core.data.DiagnosticPreferences",
                "Singleton com.yfuse.core.data.CalendarLocalStore",
                "Singleton com.yfuse.core.security.VaultCrypto",
                "Singleton com.yfuse.core.personal.LocalViewingStore",
                "Singleton com.yfuse.core.personal.PersonalLibraryRepository",
                "Singleton com.yfuse.core.data.ServerRegistry",
                "Singleton com.yfuse.core.filesource.FileSourceRegistry",
                "Singleton com.yfuse.core.filesource.FileSourceProgressStore",
                "Singleton com.yfuse.core.filesource.FileSourceClient",
                "Singleton com.yfuse.core.filesource.FileSourceLibraryStore",
                "Singleton com.yfuse.core.filesource.FileSourceScanner",
                "Singleton com.yfuse.core.data.ThemePreferences",
                "Singleton com.yfuse.core.data.TipsPreferences",
                "Singleton com.yfuse.feature.library.LibraryGridColumnsPreferences",
                "Singleton com.yfuse.core.data.HomeShelfPreferences",
                "Singleton com.yfuse.core.data.PlaybackPreferences",
                "Singleton com.yfuse.core.data.PlaybackFailoverRequest",
                "Singleton com.yfuse.core.data.PlaybackEventOutbox",
                "Singleton com.yfuse.core.sync.playback.PlaybackSyncStore",
                "Singleton com.yfuse.core.sync.ProgressSyncPreferences",
                "Singleton com.yfuse.core.data.PlaybackProgressProjection",
                "Singleton com.yfuse.core.data.ServerActivityStore",
                "Singleton com.yfuse.core.data.ServerStatsStore",
                "Singleton com.yfuse.core.data.UserAgentPreferences",
                "Singleton com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider",
                "Singleton com.yfuse.core.playback.PlaybackMediaProbeService",
                "Singleton com.yfuse.core.playback.PlaybackRuntimeEnvironmentProvider",
                "Singleton com.yfuse.core.playback.PlaybackOfflineLicenseManager",
                "Singleton com.yfuse.core.playback.PlaybackQoeReporter",
                "Singleton com.yfuse.core.data.WatchTogetherPreferences",
                "Singleton com.yfuse.core.data.DanmakuPreferences",
                "Singleton com.yfuse.core.data.SkipSegmentPreferences",
                "Singleton com.yfuse.core.data.PlaybackTrackRequest",
                "Singleton com.yfuse.core.data.LibraryCache",
                "Singleton com.yfuse.core.data.TmdbHomeCache",
                "Singleton com.yfuse.core.data.SearchHistory",
                "Singleton com.yfuse.core.data.SmartPlaylistStore",
                "Singleton com.yfuse.core.data.MetadataEditorService",
                "Singleton com.yfuse.core.network.LanDiscovery",
                "Singleton com.yfuse.feature.servers.QuickConnectGateway",
                "Singleton com.yfuse.feature.search.SearchRequests",
                "Singleton com.yfuse.core.cast.CastManager",
                "Singleton io.ktor.client.HttpClient onClose",
                "Singleton com.yfuse.core.data.EmbyRepository",
                "Singleton com.yfuse.core.offline.OfflineMediaManager",
                "Singleton com.yfuse.feature.player.PlaybackReportingCoordinator",
                "Singleton com.yfuse.core.data.ServerHealthMonitor",
                "Singleton com.yfuse.core.data.CalendarFollowStore",
                "Singleton com.yfuse.core.data.OfficialAiringScheduleCatalog",
                "Singleton com.yfuse.core.data.AiringCalendarRepository",
                "Singleton io.ktor.client.HttpClient named(danmaku-http) onClose",
                "Singleton com.yfuse.core.data.DanmakuRepository",
                "Singleton com.yfuse.core.sync.ServerSyncManager",
                "Singleton com.yfuse.core.account.AccountAccessTokenSource",
                "Singleton com.yfuse.core.sync.WatchTogetherClient",
                "Singleton com.yfuse.feature.watch.WatchInviteResolver",
                "Singleton com.yfuse.core.security.SecureStore",
                "Singleton com.yfuse.core.account.AccountApi",
                "Singleton com.yfuse.core.account.AccountRepository",
                "Singleton com.yfuse.core.account.PlaybackCloudApi",
                "Singleton com.yfuse.core.account.PlaybackVaultCipher",
                "Singleton com.yfuse.core.sync.playback.PlaybackSyncManager",
                "Singleton com.yfuse.app.ProductSession",
                "Singleton com.yfuse.core.account.PersonalAutoSync",
                "Singleton com.yfuse.core.trakt.TraktRepository onClose",
                "Singleton com.yfuse.core.handoff.HandoffPlaybackRegistry",
                "Singleton com.yfuse.core.handoff.AccountHandoffApi",
                "Singleton com.yfuse.core.handoff.HandoffVaultCipher",
                "Singleton com.yfuse.core.handoff.HandoffController",
                "Singleton io.ktor.client.HttpClient named(tmdb-http) onClose",
                "Singleton com.yfuse.core.data.TmdbRepository",
                "Singleton com.yfuse.core.data.CalendarIdentityResolver",
                "Singleton com.arkivanov.mvikotlin.core.store.StoreFactory",
            )
    }
}
