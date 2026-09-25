package com.yfuse.core.offline

import com.yfuse.core.network.embyPlaybackHeaders
import com.yfuse.core.platform.AppBuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** At most this many hops from the server's address to the file, as for playback. */
internal const val MAX_OFFLINE_REDIRECTS = 8

private val OFFLINE_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/**
 * Opens [source] for an offline transfer the way playback opens the same file: presenting the
 * client the app presents everywhere else — the configured User-Agent, and on the server's own
 * origin the same Emby identity headers — and following the server's redirects (to a CDN, a
 * storage node, a signed address) one hop at a time.
 *
 * A download used to go out as a bare `Dalvik/…` request that stopped at its first redirect. A
 * server that filters clients answered 403 to the very file the player was streaming, and one
 * that hands files out by redirect failed the download with its 302.
 *
 * Identity headers only ever go to the origin the address was built for. A hop to another origin
 * carries the User-Agent and whatever the server itself wrote into its Location, never the token;
 * HTTPS is never followed down to HTTP. [configure] adds the request's own headers — a resume
 * Range — to every hop.
 */
internal fun openOfflineTransfer(
    source: URL,
    userAgent: String,
    configure: HttpURLConnection.() -> Unit = {},
): HttpURLConnection {
    val identity = embyPlaybackHeaders(source.toString()) { AppBuildConfig.VERSION_NAME }
    var target = source
    var hops = 0
    while (true) {
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            // Media is never worth compressing, and a transparently gunzipped body would no longer
            // match the Content-Length and Content-Range the resume logic checks it against.
            connection.setRequestProperty("Accept-Encoding", "identity")
            userAgent.trim().takeIf(String::isNotEmpty)?.let { connection.setRequestProperty("User-Agent", it) }
            if (target.hasSameOriginAs(source)) {
                identity.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            }
            connection.configure()
            val next =
                offlineRedirectTarget(target, connection.responseCode, connection.getHeaderField("Location"))
                    ?: return connection
            hops += 1
            check(hops <= MAX_OFFLINE_REDIRECTS) { "下载地址重定向次数过多" }
            connection.disconnect()
            target = next
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }
}

/**
 * Where a redirect answered with [code] leads, or null when it is not a redirect — then it is the
 * answer itself. A hop anywhere but HTTP(S), or from HTTPS down to HTTP, is refused.
 */
internal fun offlineRedirectTarget(
    current: URL,
    code: Int,
    location: String?,
): URL? {
    if (code !in OFFLINE_REDIRECT_CODES) return null
    val spec = location?.trim().orEmpty()
    if (spec.isEmpty()) return null
    val next = URL(current, spec)
    val scheme = next.protocol.lowercase()
    check(scheme == "https" || scheme == "http") { "下载地址重定向到了不支持的协议" }
    check(!(current.protocol.equals("https", ignoreCase = true) && scheme == "http")) {
        "下载地址不能从 HTTPS 重定向到 HTTP"
    }
    check(next.userInfo == null) { "下载地址不安全" }
    return next
}

private fun URL.hasSameOriginAs(other: URL): Boolean =
    protocol.equals(other.protocol, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun URL.effectivePort(): Int = if (port != -1) port else defaultPort
