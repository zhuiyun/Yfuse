package com.yfuse.feature.server

import app.cash.turbine.test
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.feature.json
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

class ServerStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun store(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Store<ServerIntent, ServerState, ServerLabel> {
        val (repo, _) = testRepo(handler)
        return ServerStoreFactory(DefaultStoreFactory(), repo).create()
    }

    @Test
    fun connect_success_emits_connected_with_name() = runTest {
        val store = store { json("""{"ServerName":"zhuiyun","Version":"4.9.1.90"}""") }
        store.accept(ServerIntent.UrlChanged("http://host:8096"))

        store.labels.test {
            store.accept(ServerIntent.Connect)
            assertEquals(
                ServerLabel.Connected("http://host:8096", "zhuiyun"),
                awaitItem(),
            )
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun connect_failure_sets_error() = runTest {
        val store = store { respond(content = "", status = HttpStatusCode.InternalServerError) }
        store.accept(ServerIntent.UrlChanged("http://host:8096"))
        store.accept(ServerIntent.Connect)

        val s = store.states.first { !it.loading && it.error != null }
        assertEquals("服务器错误(500)", s.error)
    }
}
