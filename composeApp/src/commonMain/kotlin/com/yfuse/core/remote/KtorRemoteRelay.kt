package com.yfuse.core.remote

import com.yfuse.core.account.ACCOUNT_BASE_URL
import com.yfuse.core.network.embyHttpEngine
import com.yfuse.core.sync.WATCH_AUTH_CLOSE_REASONS
import com.yfuse.core.sync.WatchAuthenticationException
import com.yfuse.core.sync.toWebSocketUrl
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.json.Json

/** 手机遥控 rides the watch relay of the account service; its socket is the same `/watch`. */
internal val REMOTE_RELAY_URL: String? = ACCOUNT_BASE_URL.toWebSocketUrl()

/** The watch relay's own socket with the account bearer; one client for the process, as 一起看 has. */
object KtorRemoteRelayConnector : RemoteRelayConnector {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val client by lazy { HttpClient(embyHttpEngine()) { install(WebSockets) } }

    override suspend fun connect(
        url: String,
        accessToken: String,
        session: suspend (RemoteRelayChannel) -> Unit,
    ) {
        client.webSocket(
            urlString = url,
            request = { bearerAuth(accessToken) },
        ) {
            val socket = this
            var ended = false
            session(
                object : RemoteRelayChannel {
                    override suspend fun send(message: WatchWireMessage) {
                        socket.send(json.encodeToString(WatchWireMessage.serializer(), message))
                    }

                    override suspend fun receive(): WatchWireMessage? {
                        while (true) {
                            val frame = socket.incoming.receiveCatching().getOrNull()
                            if (frame == null) {
                                ended = true
                                return null
                            }
                            if (frame !is Frame.Text) continue
                            runCatching {
                                json.decodeFromString(WatchWireMessage.serializer(), frame.readText())
                            }.getOrNull()?.let { return it }
                        }
                    }
                },
            )
            // Only a socket the relay closed has a reason to read; one this side is leaving has none yet.
            if (ended) {
                val closed = runCatching { closeReason.await() }.getOrNull()
                if (closed?.code == CloseReason.Codes.VIOLATED_POLICY.code &&
                    closed.message in WATCH_AUTH_CLOSE_REASONS
                ) {
                    throw WatchAuthenticationException()
                }
            }
        }
    }
}
