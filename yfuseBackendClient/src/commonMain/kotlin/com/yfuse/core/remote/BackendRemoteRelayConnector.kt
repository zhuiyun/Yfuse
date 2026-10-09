package com.yfuse.core.remote

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendEndpoints
import com.yfuse.backend.BackendFeature
import com.yfuse.core.sync.receiveRelayText
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.send
import kotlinx.serialization.json.Json

/** 手机遥控 rides the watch relay of the account service; its socket is the same `/watch`. */
val REMOTE_RELAY_URL: String = BackendEndpoints.ORIGIN.replaceFirst("https://", "wss://") + "/watch"

/** The watch relay's own socket with the account bearer; one client for the process, as 一起看 has. */
class BackendRemoteRelayConnector(
    private val engineFactory: () -> HttpClientEngine,
    private val backendAccess: BackendAccess = BackendAccess.Default,
) : RemoteRelayConnector {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val client by lazy { HttpClient(engineFactory()) { install(WebSockets) } }

    override suspend fun connect(
        url: String,
        accessToken: String,
        session: suspend (RemoteRelayChannel) -> Unit,
    ) {
        backendAccess.requireEnabled(BackendFeature.RemoteControl)
        client.webSocket(
            urlString = url,
            request = { bearerAuth(accessToken) },
        ) {
            val socket = this
            session(
                object : RemoteRelayChannel {
                    override suspend fun send(message: WatchWireMessage) {
                        socket.send(json.encodeToString(WatchWireMessage.serializer(), message))
                    }

                    override suspend fun receive(): WatchWireMessage? {
                        while (true) {
                            val text = receiveRelayText(socket.incoming, socket.closeReason) ?: return null
                            runCatching {
                                json.decodeFromString(WatchWireMessage.serializer(), text)
                            }.getOrNull()?.let { return it }
                        }
                    }
                },
            )
        }
    }
}
