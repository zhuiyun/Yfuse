package com.yfuse.feature.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.network.isLocalServiceHost
import com.yfuse.core.network.localNetworkConnectionsRestricted
import com.yfuse.core.network.localNetworkPermissionGranted
import com.yfuse.core.network.rememberLocalNetworkPermissionRequest
import io.ktor.http.Url

/** Loopback stays inside this app's profile and does not require LAN permission. */
internal fun serverUsesLocalNetwork(url: String): Boolean {
    val host =
        runCatching {
            Url(url)
                .host
                .lowercase()
                .removeSurrounding("[", "]")
                .removePrefix("::ffff:")
        }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return false
    if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host.startsWith("127.")) return false
    return host.isLocalServiceHost()
}

internal fun ServersIntent.connectsToServer(): Boolean =
    this == ServersIntent.Submit ||
        this == ServersIntent.StartQuickConnect ||
        this is ServersIntent.SelectDiscovered ||
        this is ServersIntent.SelectPlexCloudServer

internal fun connectionIntentAfterPermission(
    action: ServersIntent,
    endpoint: String,
    granted: Boolean,
): ServersIntent =
    if (!granted && serverUsesLocalNetwork(endpoint)) ServersIntent.LocalNetworkPermissionDenied else action

/** Ask on explicit connection actions, including hostnames whose DNS can resolve to a LAN. */
@Composable
fun rememberServerConnectionIntent(
    state: ServersState,
    onIntent: (ServersIntent) -> Unit,
): (ServersIntent) -> Unit {
    var pending by remember { mutableStateOf<ServersIntent?>(null) }
    val currentState by rememberUpdatedState(state)
    val dispatch by rememberUpdatedState(onIntent)

    fun complete(granted: Boolean) {
        val action = pending ?: return
        pending = null
        if (!currentState.dialogVisible) return
        val endpoint = (action as? ServersIntent.SelectDiscovered)?.server?.address ?: currentState.form.url
        // A refused LAN permission must not prevent connecting to an Internet server.
        dispatch(connectionIntentAfterPermission(action, endpoint, granted))
    }
    val request = rememberLocalNetworkPermissionRequest(onGranted = { complete(true) }, onDenied = { complete(false) })
    return { action ->
        if (action.connectsToServer() && localNetworkConnectionsRestricted() && !localNetworkPermissionGranted()) {
            if (pending == null) {
                pending = action
                request()
            }
        } else {
            dispatch(action)
        }
    }
}
