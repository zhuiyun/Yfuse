package com.yfuse.core.remote

import com.yfuse.core.data.AuthedServer
import com.yfuse.core.model.MediaServerKind
import com.yfuse.watch.protocol.RemoteSignInServer
import com.yfuse.watch.protocol.WatchProtocol

/**
 * Where a television's 用手机登录 stands — see [RemoteControlHost.askForServer]. While it is
 * [asking], its handoff heartbeat says so, and a phone of the same account lists it on 设备接力.
 */
sealed interface RemoteSignInRequest {
    val asking: Boolean
        get() = this == Waiting || this is Offered

    /** Not asking. */
    data object Idle : RemoteSignInRequest

    /** Asking, and no phone has put a server before it — or the one that had has gone. */
    data object Waiting : RemoteSignInRequest

    /**
     * The phone of [phoneId], calling itself [phoneName], puts [server] before this television. It
     * comes without its session, which follows only once that phone's user confirms there.
     */
    data class Offered(
        val phoneId: String,
        val phoneName: String?,
        val server: RemoteSignInServer,
    ) : RemoteSignInRequest

    /** The session came, and [server] is being asked to accept it before it is saved. */
    data class Receiving(
        val phoneName: String?,
        val server: RemoteSignInServer,
    ) : RemoteSignInRequest

    /** No phone came in time; the panel says so until it is closed or asked again. */
    data object Expired : RemoteSignInRequest
}

/**
 * Whether a relay at [url] may carry a server's session: only inside TLS. Over a plain socket the
 * session would cross the network in the clear, so 用手机登录 is neither asked for nor sent there.
 */
internal fun remoteSignInCarried(url: String): Boolean = url.startsWith("wss://")

/**
 * The server a phone handed this television, as this television's own sign-in would have left it,
 * so the one save path keeps it — session in the secure store. Null for anything the relay should
 * never have passed on.
 */
fun RemoteSignInServer.toAuthedServer(): AuthedServer? {
    if (!WatchProtocol.isValidRemoteSignInCredentials(this)) return null
    val mediaKind =
        when (kind) {
            "Emby" -> MediaServerKind.Emby
            "Jellyfin" -> MediaServerKind.Jellyfin
            else -> return null
        }
    return AuthedServer(
        baseUrl = baseUrl,
        serverName = serverName,
        userId = userId ?: return null,
        userName = userName,
        accessToken = accessToken ?: return null,
        kind = mediaKind,
    )
}
