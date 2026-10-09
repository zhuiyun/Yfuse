package com.yfuse.core.sync

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendFeature
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope

/** An authenticated account relay rejected the current session. */
class WatchAuthenticationException : Exception("一起看登录状态已失效")

class AccountRequiredForWatchException : Exception("请先登录 Yfuse 账号后使用一起看")

/** Socket operations without Ktor types; room ownership and timeline policy stay in the app. */
interface WatchRelaySession : CoroutineScope {
    suspend fun send(text: String)

    /** Ignores control/binary frames. A clean end returns null; an auth close throws. */
    suspend fun receiveText(): String?

    suspend fun close(reason: String)
}

fun interface WatchRelayConnector {
    suspend fun connect(
        url: String,
        accessToken: suspend () -> String?,
        session: suspend WatchRelaySession.() -> Unit,
    )
}

/** Engine creation and token restoration happen only after the backend capability gate. */
class KtorWatchRelayConnector(
    private val engineFactory: () -> HttpClientEngine,
    private val backendAccess: BackendAccess = BackendAccess.Default,
) : WatchRelayConnector {
    private val client by lazy { HttpClient(engineFactory()) { install(WebSockets) } }

    override suspend fun connect(
        url: String,
        accessToken: suspend () -> String?,
        session: suspend WatchRelaySession.() -> Unit,
    ) {
        backendAccess.requireEnabled(BackendFeature.WatchTogether)
        val token = accessToken() ?: throw AccountRequiredForWatchException()
        client.webSocket(
            urlString = url,
            request = { bearerAuth(token) },
        ) {
            val socket = this
            session(
                object : WatchRelaySession {
                    override val coroutineContext = socket.coroutineContext

                    override suspend fun send(text: String) {
                        socket.send(text)
                    }

                    override suspend fun receiveText(): String? = receiveRelayText(socket.incoming, socket.closeReason)

                    override suspend fun close(reason: String) {
                        socket.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                    }
                },
            )
        }
    }
}

internal val WATCH_AUTH_CLOSE_REASONS = setOf("account_auth_required", "account_auth_expired")
