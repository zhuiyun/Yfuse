package com.yfuse.feature.login

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

class LoginStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun store(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Store<LoginIntent, LoginState, LoginLabel> {
        val (repo, _) = testRepo(handler)
        return LoginStoreFactory(DefaultStoreFactory(), repo, "http://host:8096").create()
    }

    @Test
    fun submit_success_emits_navigate_home() = runTest {
        val store = store { json("""{"AccessToken":"tok","User":{"Id":"u1","Name":"zhuiyun"}}""") }
        store.accept(LoginIntent.UsernameChanged("zhuiyun"))
        store.accept(LoginIntent.PasswordChanged("123456"))

        store.labels.test {
            store.accept(LoginIntent.Submit)
            assertEquals(LoginLabel.NavigateHome, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun submit_failure_sets_error() = runTest {
        val store = store { respond(content = "", status = HttpStatusCode.Unauthorized) }
        store.accept(LoginIntent.UsernameChanged("x"))
        store.accept(LoginIntent.PasswordChanged("y"))
        store.accept(LoginIntent.Submit)

        val s = store.states.first { !it.loading && it.error != null }
        assertEquals("用户名或密码错误", s.error)
    }

    @Test
    fun blank_credentials_do_not_submit() = runTest {
        val store = store { json("{}") }
        // username blank -> Submit is a no-op, no loading, no error
        store.accept(LoginIntent.Submit)
        assertEquals(LoginState(), store.state)
    }
}
