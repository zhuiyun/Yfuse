package com.yfuse.feature.servers

import app.cash.turbine.test
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.AuthedServer
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.authRoutes
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

class ServersStoreTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun store(
        registry: ServerRegistry,
        quickConnectGateway: QuickConnectGateway = UnsupportedQuickConnectGateway,
        nowEpochMs: () -> Long = { System.currentTimeMillis() },
        onAuthenticated: (String) -> Unit = {},
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Store<ServersIntent, ServersState, ServersLabel> =
        ServersStoreFactory(
            storeFactory = DefaultStoreFactory(),
            repo = testRepo(handler = handler),
            registry = registry,
            quickConnectGateway = quickConnectGateway,
            nowEpochMs = nowEpochMs,
            onAuthenticated = onAuthenticated,
        ).create()

    @Test
    fun submit_adds_http_server_without_confirmation_and_emits_label() =
        runTest {
            val registry = ServerRegistry(MapSettings(), TestSecureStore())
            val authenticated = mutableListOf<String>()
            val store =
                store(
                    registry = registry,
                    onAuthenticated = authenticated::add,
                ) { req -> authRoutes(req) }
            store.accept(ServersIntent.ProtocolChanged(https = false))
            store.accept(ServersIntent.HostChanged("192.168.1.8"))
            store.accept(ServersIntent.PortChanged("8096"))
            store.accept(ServersIntent.UsernameChanged("zhuiyun"))
            store.accept(ServersIntent.PasswordChanged("123456"))

            store.labels.test {
                store.accept(ServersIntent.Submit)
                assertEquals(ServersLabel.ServerAdded(first = true), awaitItem())
                cancelAndConsumeRemainingEvents()
            }

            assertEquals("已连接「zhuiyun」", store.state.notice)
            assertEquals(1, registry.data.value.servers.size)
            assertEquals(
                "zhuiyun",
                registry.data.value.servers
                    .first()
                    .serverName,
            )
            assertEquals(
                listOf(
                    registry.data.value.servers
                        .single()
                        .id,
                ),
                authenticated,
            )
            store.dispose()
        }

    @Test
    fun submit_uses_custom_server_name() =
        runTest {
            val registry = testRegistry()
            val store = store(registry) { req -> authRoutes(req) }
            store.accept(ServersIntent.ServerNameChanged("  客厅影院  "))
            store.accept(ServersIntent.ProtocolChanged(https = false))
            store.accept(ServersIntent.HostChanged("192.168.1.8"))
            store.accept(ServersIntent.PortChanged("8096"))
            store.accept(ServersIntent.UsernameChanged("zhuiyun"))
            store.accept(ServersIntent.PasswordChanged("123456"))
            store.accept(ServersIntent.HttpRiskAcceptedChanged(true))

            store.labels.test {
                store.accept(ServersIntent.Submit)
                assertEquals(ServersLabel.ServerAdded(first = true), awaitItem())
                cancelAndConsumeRemainingEvents()
            }

            assertEquals("已连接「客厅影院」", store.state.notice)
            assertEquals(
                "客厅影院",
                registry.data.value.servers
                    .single()
                    .serverName,
            )
            store.dispose()
        }

    @Test
    fun editing_display_name_keeps_session_and_does_not_reauthenticate() =
        runTest {
            val registry = testRegistry()
            val existing =
                SavedServer(
                    id = SavedServer.idOf("http://host", "u"),
                    baseUrl = "http://host",
                    serverName = "旧名称",
                    userId = "u",
                    userName = "zhuiyun",
                    accessToken = "existing-token",
                    localCleartextConfirmed = true,
                )
            registry.addOrUpdate(existing)
            val store = store(registry) { error("rename must not make a network request") }
            store.states.first { it.servers.isNotEmpty() }

            store.accept(ServersIntent.EditServer(existing))
            assertEquals("旧名称", store.state.form.serverName)
            store.accept(ServersIntent.ServerNameChanged("  家庭影院  "))
            store.accept(ServersIntent.Submit)

            val renamed = registry.defaultServer
            assertEquals(existing.id, renamed?.id)
            assertEquals("家庭影院", renamed?.serverName)
            assertEquals("existing-token", renamed?.accessToken)
            assertEquals(false, store.state.dialogVisible)
            assertEquals(null, store.state.editingServerId)
            assertEquals("已更新「家庭影院」", store.state.notice)
            store.dispose()
        }

    @Test
    fun a_server_added_beside_others_is_not_the_first() =
        runTest {
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer("id1", "http://h", "N", "u", "user", "tok", localCleartextConfirmed = true),
            )
            val store = store(registry) { req -> authRoutes(req) }
            store.states.first { it.servers.isNotEmpty() }
            store.accept(ServersIntent.HostChanged("https://media.example.com"))
            store.accept(ServersIntent.UsernameChanged("zhuiyun"))
            store.accept(ServersIntent.PasswordChanged("123456"))

            store.labels.test {
                store.accept(ServersIntent.Submit)
                assertEquals(ServersLabel.ServerAdded(first = false), awaitItem())
                cancelAndConsumeRemainingEvents()
            }

            assertEquals(2, registry.data.value.servers.size)
            store.dispose()
        }

    @Test
    fun editing_connection_reauthenticates_and_keeps_the_custom_name() =
        runTest {
            val settings = MapSettings()
            val registry = ServerRegistry(settings, TestSecureStore())
            val existing =
                SavedServer(
                    id = SavedServer.idOf("http://oldhost:8096", "old-user-id"),
                    baseUrl = "http://oldhost:8096",
                    serverName = "家庭影院",
                    userId = "old-user-id",
                    userName = "zhuiyun",
                    accessToken = "old-token",
                    localCleartextConfirmed = true,
                )
            registry.addOrUpdate(existing)
            val oldCacheKey = "library.cache.${existing.id}"
            settings.putString(oldCacheKey, "cached-home")
            val other =
                SavedServer(
                    id = SavedServer.idOf("http://other:8096", "other-user"),
                    baseUrl = "http://other:8096",
                    serverName = "其他服务器",
                    userId = "other-user",
                    userName = "other",
                    accessToken = "other-token",
                    localCleartextConfirmed = true,
                )
            registry.addOrUpdate(other)
            val store =
                store(registry) { request ->
                    authRoutes(
                        request,
                        authBody =
                            """{"AccessToken":"new-token","User":{"Id":"u1","Name":"zhuiyun"}}""",
                    )
                }
            store.states.first { it.servers.isNotEmpty() }
            store.accept(ServersIntent.EditServer(existing))
            store.accept(ServersIntent.HostChanged("newhost"))
            store.accept(ServersIntent.PasswordChanged("password"))

            store.labels.test {
                store.accept(ServersIntent.Submit)
                assertEquals(ServersLabel.ServerAdded(first = false), awaitItem())
                cancelAndConsumeRemainingEvents()
            }

            assertEquals("已更新「家庭影院」", store.state.notice)

            val updated =
                registry.data.value.servers
                    .first { it.baseUrl == "http://newhost:8096" }
            assertEquals(2, registry.data.value.servers.size)
            assertEquals("家庭影院", updated.serverName)
            assertEquals("new-token", updated.accessToken)
            assertEquals("http://newhost:8096", updated.baseUrl)
            // Stale routes and queued offline downloads keep resolving after an address edit.
            assertEquals(updated, registry.serverById(existing.id))
            assertTrue(existing.id in updated.previousIds)
            assertNull(settings.getStringOrNull(oldCacheKey))
            assertEquals(other, registry.serverById(other.id))
            assertEquals(updated.id, registry.data.value.defaultServerId)
            store.dispose()
        }

    /** A registry whose active profile is a child's, holding the one server it was given. */
    private suspend fun childRegistry(server: SavedServer): ServerRegistry {
        val settings = MapSettings()
        val personal = PersonalLibraryRepository(settings)
        val registry = ServerRegistry(settings, TestSecureStore(), personal = personal)
        registry.addOrUpdate(server)
        personal.setGuardianPin("583921".toCharArray()).getOrThrow()
        personal.saveProfile(name = "孩子", child = true, serverIds = setOf(server.id)).getOrThrow()
        val child =
            personal.state.value.profiles
                .last()
                .id
        personal.switchProfile(child).getOrThrow()
        return registry
    }

    private val kidServer = SavedServer("kid", "https://media.example", "影院", "kid", "儿童", "token")

    @Test
    fun a_child_profile_removing_a_server_is_refused_instead_of_crashing() =
        runTest {
            val registry = childRegistry(kidServer)
            val store = store(registry) { error("a refused removal must not make a network request") }
            store.states.first { it.servers.isNotEmpty() }

            store.accept(ServersIntent.Remove(kidServer.id))

            assertEquals(
                listOf(kidServer.id),
                registry.data.value.servers
                    .map { it.id },
            )
            assertEquals("请先使用家长 PIN 切换到成人资料", store.state.notice)
            store.dispose()
        }

    @Test
    fun a_child_profile_renaming_a_server_is_refused_instead_of_crashing() =
        runTest {
            val registry = childRegistry(kidServer)
            val store = store(registry) { error("a rename must not make a network request") }
            store.states.first { it.servers.isNotEmpty() }
            store.accept(ServersIntent.EditServer(kidServer))
            store.accept(ServersIntent.ServerNameChanged("客厅"))

            store.accept(ServersIntent.Submit)

            assertEquals("影院", registry.serverById(kidServer.id)?.serverName)
            assertEquals("请先使用家长 PIN 切换到成人资料", store.state.form.error)
            assertTrue(store.state.dialogVisible)
            store.dispose()
        }

    @Test
    fun a_child_profile_adding_a_server_is_refused_instead_of_crashing() =
        runTest {
            val registry = childRegistry(kidServer)
            val store = store(registry) { req -> authRoutes(req) }
            store.accept(ServersIntent.OpenAddDialog)
            store.accept(ServersIntent.HostChanged("https://other.example"))
            store.accept(ServersIntent.UsernameChanged("zhuiyun"))
            store.accept(ServersIntent.PasswordChanged("123456"))

            store.accept(ServersIntent.Submit)

            val refused = store.states.first { it.form.error != null }
            assertEquals("请先使用家长 PIN 切换到成人资料", refused.form.error)
            assertEquals(
                listOf(kidServer.id),
                registry.data.value.servers
                    .map { it.id },
            )
            store.dispose()
        }

    @Test
    fun submit_failure_sets_form_error() =
        runTest {
            val store = store(testRegistry()) { respond(content = "", status = HttpStatusCode.Unauthorized) }
            store.accept(ServersIntent.ProtocolChanged(https = false))
            store.accept(ServersIntent.HostChanged("192.168.1.8"))
            store.accept(ServersIntent.PortChanged("8096"))
            store.accept(ServersIntent.UsernameChanged("x"))
            store.accept(ServersIntent.PasswordChanged("y"))
            store.accept(ServersIntent.HttpRiskAcceptedChanged(true))
            store.accept(ServersIntent.Submit)

            val s = store.states.first { it.form.error != null }
            assertEquals("认证失败，请检查登录信息或重新登录该服务器", s.form.error)
            store.dispose()
        }

    @Test
    fun existing_registry_servers_reflected_in_state() =
        runTest {
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer(
                    "id1",
                    "http://h",
                    "N",
                    "u",
                    "user",
                    "tok",
                    localCleartextConfirmed = true,
                ),
            )
            val store = store(registry) { authRoutes(it) }

            val s = store.states.first { it.servers.isNotEmpty() }
            assertEquals(1, s.servers.size)
            assertEquals("N", s.servers.first().serverName)
            store.dispose()
        }

    @Test
    fun denied_local_network_permission_surfaces_an_actionable_scan_error() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.OpenAddDialog)

            store.accept(ServersIntent.LocalNetworkPermissionDenied)

            assertEquals(
                "需要允许“附近的设备”访问才能连接局域网服务器或投屏；可在设置的权限检查中开启",
                store.state.scanError,
            )
            assertFalse(store.state.scanning)
            assertEquals(store.state.scanError, store.state.form.error)
            assertTrue(store.state.discovered.isEmpty())
            store.dispose()
        }

    @Test
    fun protocol_switch_updates_the_default_port() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }

            assertEquals("443", store.state.form.port)
            store.accept(ServersIntent.ProtocolChanged(https = false))
            assertEquals("8096", store.state.form.port)
            store.accept(ServersIntent.ProtocolChanged(https = true))
            assertEquals("443", store.state.form.port)

            store.dispose()
        }

    @Test
    fun address_with_http_scheme_updates_protocol_and_avoids_duplicate_scheme() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }

            store.accept(ServersIntent.HostChanged("http://media.example.com"))

            assertEquals(false, store.state.form.https)
            assertEquals("media.example.com", store.state.form.host)
            assertEquals("8096", store.state.form.port)
            assertEquals("http://media.example.com:8096", store.state.form.url)
            store.dispose()
        }

    @Test
    fun address_without_scheme_keeps_selected_protocol_and_explicit_port() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.ProtocolChanged(https = false))

            store.accept(ServersIntent.HostChanged("192.168.1.8:19001"))

            assertEquals(false, store.state.form.https)
            assertEquals("192.168.1.8", store.state.form.host)
            assertEquals("19001", store.state.form.port)
            assertEquals("http://192.168.1.8:19001", store.state.form.url)
            store.dispose()
        }

    @Test
    fun address_with_https_and_explicit_port_overrides_both_fields() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.ProtocolChanged(https = false))

            store.accept(ServersIntent.HostChanged("https://media.example.com:9443"))

            assertEquals(true, store.state.form.https)
            assertEquals("media.example.com", store.state.form.host)
            assertEquals("9443", store.state.form.port)
            assertEquals("https://media.example.com:9443", store.state.form.url)
            store.dispose()
        }

    @Test
    fun complete_url_paste_splits_scheme_host_port_and_base_path() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }

            store.accept(ServersIntent.HostChanged("https://media.example.com:9443/emby/"))

            assertTrue(store.state.form.https)
            assertEquals("media.example.com", store.state.form.host)
            assertEquals("9443", store.state.form.port)
            assertEquals("/emby", store.state.form.basePath)
            assertEquals("https://media.example.com:9443/emby", store.state.form.url)
            store.dispose()
        }

    @Test
    fun a_lan_address_defaults_to_http_on_8096() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }

            store.accept(ServersIntent.HostChanged("192.168.1.8"))

            assertFalse(store.state.form.https)
            assertEquals("8096", store.state.form.port)
            assertEquals("http://192.168.1.8:8096", store.state.form.url)
            store.dispose()
        }

    @Test
    fun a_lan_address_with_its_port_still_gets_http() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }

            store.accept(ServersIntent.HostChanged("nas.local:8096"))

            assertEquals("http://nas.local:8096", store.state.form.url)
            store.dispose()
        }

    @Test
    fun a_public_host_after_a_lan_one_goes_back_to_https() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.HostChanged("192.168.1.8"))

            store.accept(ServersIntent.HostChanged("media.example.com"))

            assertEquals("https://media.example.com:443", store.state.form.url)
            store.dispose()
        }

    @Test
    fun a_picked_protocol_and_port_are_kept_for_a_lan_address() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.ProtocolChanged(https = true))
            store.accept(ServersIntent.PortChanged("8920"))

            store.accept(ServersIntent.HostChanged("192.168.1.8"))

            assertEquals("https://192.168.1.8:8920", store.state.form.url)
            store.dispose()
        }

    @Test
    fun plex_on_a_lan_address_gets_http_and_keeps_its_own_port() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.ProviderChanged(MediaServerKind.Plex))

            store.accept(ServersIntent.HostChanged("192.168.1.8"))

            assertEquals("http://192.168.1.8:32400", store.state.form.url)
            store.dispose()
        }

    @Test
    fun host_without_scheme_defaults_to_https() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }

            store.accept(ServersIntent.HostChanged("media.example.com/emby"))

            assertTrue(store.state.form.https)
            assertEquals("https://media.example.com:443/emby", store.state.form.url)
            store.dispose()
        }

    @Test
    fun httpCanSubmitWithoutRiskConfirmation() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.HostChanged("http://192.168.1.8:8096/emby"))
            store.accept(ServersIntent.UsernameChanged("user"))

            assertTrue(store.state.form.canSubmit)
            store.dispose()
        }

    @Test
    fun publicHttpCanSubmitWithoutRiskConfirmation() =
        runTest {
            val registry = testRegistry()
            val store = store(registry) { req -> authRoutes(req) }
            store.accept(ServersIntent.HostChanged("http://media.example.com:8096"))
            store.accept(ServersIntent.UsernameChanged("user"))

            assertTrue(store.state.form.canSubmit)
            store.dispose()
        }

    @Test
    fun invalid_port_cannot_submit_or_start_quick_connect() =
        runTest {
            val store = store(testRegistry()) { authRoutes(it) }
            store.accept(ServersIntent.HostChanged("media.example.com"))
            store.accept(ServersIntent.PortChanged("70000"))
            store.accept(ServersIntent.UsernameChanged("user"))

            assertFalse(store.state.form.canSubmit)
            assertFalse(store.state.form.canStartQuickConnect)
            store.dispose()
        }

    @Test
    fun unsupported_quick_connect_is_reported_without_persisting_a_server() =
        runTest {
            val registry = testRegistry()
            val store = store(registry) { authRoutes(it) }
            store.accept(ServersIntent.HostChanged("media.example.com"))

            store.accept(ServersIntent.StartQuickConnect)

            assertEquals(
                QuickConnectUiState.Unsupported(QuickConnectUnsupportedMessage),
                store.state.quickConnect,
            )
            assertTrue(
                registry.data.value.servers
                    .isEmpty(),
            )
            store.dispose()
        }

    @Test
    fun quick_connect_persists_only_the_authenticated_server_returned_by_gateway() =
        runTest {
            val registry = testRegistry()
            val gateway =
                object : QuickConnectGateway {
                    override suspend fun start(baseUrl: String) =
                        Result.success(
                            QuickConnectStartResult.AwaitingApproval(
                                QuickConnectSession(id = "session", code = "123456", expiresAtEpochMs = 10_000L),
                            ),
                        )

                    override suspend fun poll(
                        baseUrl: String,
                        sessionId: String,
                    ) = Result.success(
                        QuickConnectPollResult.Authenticated(
                            AuthedServer(
                                baseUrl = baseUrl,
                                serverName = "Home Emby",
                                userId = "user-id",
                                userName = "User",
                                accessToken = "real-server-token",
                            ),
                        ),
                    )

                    override suspend fun cancel(
                        baseUrl: String,
                        sessionId: String,
                    ) = Result.success(Unit)
                }
            val store =
                store(
                    registry = registry,
                    handler = { authRoutes(it) },
                    quickConnectGateway = gateway,
                    nowEpochMs = { 0L },
                )
            store.accept(ServersIntent.HostChanged("media.example.com"))

            store.accept(ServersIntent.StartQuickConnect)
            assertEquals(
                QuickConnectUiState.AwaitingApproval("123456", 10_000L),
                store.state.quickConnect,
            )
            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(
                "real-server-token",
                registry.data.value.servers
                    .single()
                    .accessToken,
            )
            assertEquals(
                "https://media.example.com:443",
                registry.data.value.servers
                    .single()
                    .baseUrl,
            )
            store.dispose()
        }
}
