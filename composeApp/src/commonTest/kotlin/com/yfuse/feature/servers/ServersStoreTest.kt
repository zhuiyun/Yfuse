package com.yfuse.feature.servers

import app.cash.turbine.test
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServersStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun store(
        registry: ServerRegistry,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Store<ServersIntent, ServersState, ServersLabel> =
        ServersStoreFactory(DefaultStoreFactory(), testRepo(handler), registry).create()

    @Test
    fun submit_adds_server_and_emits_label() = runTest {
        val registry = testRegistry()
        val store = store(registry) { req -> authRoutes(req) }
        store.accept(ServersIntent.ProtocolChanged(https = false))
        store.accept(ServersIntent.HostChanged("host"))
        store.accept(ServersIntent.PortChanged("8096"))
        store.accept(ServersIntent.UsernameChanged("zhuiyun"))
        store.accept(ServersIntent.PasswordChanged("123456"))

        store.labels.test {
            store.accept(ServersIntent.Submit)
            assertEquals(ServersLabel.ServerAdded, awaitItem())
            cancelAndConsumeRemainingEvents()
        }

        assertEquals(1, registry.data.value.servers.size)
        assertEquals("zhuiyun", registry.data.value.servers.first().serverName)
        store.dispose()
    }

    @Test
    fun submit_failure_sets_form_error() = runTest {
        val store = store(testRegistry()) { respond(content = "", status = HttpStatusCode.Unauthorized) }
        store.accept(ServersIntent.ProtocolChanged(https = false))
        store.accept(ServersIntent.HostChanged("host"))
        store.accept(ServersIntent.PortChanged("8096"))
        store.accept(ServersIntent.UsernameChanged("x"))
        store.accept(ServersIntent.PasswordChanged("y"))
        store.accept(ServersIntent.Submit)

        val s = store.states.first { it.form.error != null }
        assertEquals("用户名或密码错误", s.form.error)
        store.dispose()
    }

    @Test
    fun existing_registry_servers_reflected_in_state() = runTest {
        val registry = testRegistry()
        registry.addOrUpdate(SavedServer("id1", "http://h", "N", "u", "user", "tok"))
        val store = store(registry) { authRoutes(it) }

        val s = store.states.first { it.servers.isNotEmpty() }
        assertEquals(1, s.servers.size)
        assertEquals("N", s.servers.first().serverName)
        store.dispose()
    }

    @Test
    fun protocol_switch_updates_the_default_port() = runTest {
        val store = store(testRegistry()) { authRoutes(it) }

        assertEquals("443", store.state.form.port)
        store.accept(ServersIntent.ProtocolChanged(https = false))
        assertEquals("8096", store.state.form.port)
        store.accept(ServersIntent.ProtocolChanged(https = true))
        assertEquals("443", store.state.form.port)

        store.dispose()
    }

    @Test
    fun address_with_http_scheme_updates_protocol_and_avoids_duplicate_scheme() = runTest {
        val store = store(testRegistry()) { authRoutes(it) }

        store.accept(ServersIntent.HostChanged("http://media.example.com"))

        assertEquals(false, store.state.form.https)
        assertEquals("media.example.com", store.state.form.host)
        assertEquals("8096", store.state.form.port)
        assertEquals("http://media.example.com:8096", store.state.form.url)
        store.dispose()
    }

    @Test
    fun address_without_scheme_keeps_selected_protocol_and_explicit_port() = runTest {
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
    fun address_with_https_and_explicit_port_overrides_both_fields() = runTest {
        val store = store(testRegistry()) { authRoutes(it) }
        store.accept(ServersIntent.ProtocolChanged(https = false))

        store.accept(ServersIntent.HostChanged("https://media.example.com:9443"))

        assertEquals(true, store.state.form.https)
        assertEquals("media.example.com", store.state.form.host)
        assertEquals("9443", store.state.form.port)
        assertEquals("https://media.example.com:9443", store.state.form.url)
        store.dispose()
    }
}
