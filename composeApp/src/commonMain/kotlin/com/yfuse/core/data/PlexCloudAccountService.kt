package com.yfuse.core.data

import com.yfuse.core.data.dto.PlexResponseDto
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.model.normalizedRoutes
import com.yfuse.core.network.suppressEmbyIdentity
import com.yfuse.deviceId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val PLEX_CLOUD_ORIGIN = "https://plex.tv"
private const val PLEX_AUTH_ORIGIN = "https://app.plex.tv"
private const val PLEX_METADATA_ORIGIN = "https://metadata.provider.plex.tv"
private const val PLEX_CLOUD_PRODUCT = "Yfuse"
private const val PLEX_CLOUD_PLATFORM = "Android"

data class PlexPinSession(
    val id: Long,
    val code: String,
    val authUrl: String,
    val expiresAtEpochMs: Long,
)

data class PlexPinPoll(
    val accessToken: String?,
    val expired: Boolean,
)

data class PlexHomeUser(
    val id: String,
    val name: String,
    val pinProtected: Boolean,
    val admin: Boolean,
)

data class PlexCloudConnection(
    val uri: String,
    val local: Boolean,
    val relay: Boolean,
)

data class PlexCloudResource(
    val id: String,
    val name: String,
    val owned: Boolean,
    val accessToken: String?,
    val connections: List<PlexCloudConnection>,
) {
    /** Secure non-relay routes first; relay remains a last-resort remote route. */
    fun rankedConnections(): List<PlexCloudConnection> =
        connections
            .filter { ServerRoute.sanitizeUrl(it.uri) != null }
            .distinctBy { it.uri.trimEnd('/') }
            .sortedWith(
                compareBy<PlexCloudConnection>(
                    { !it.uri.startsWith("https://") },
                    { it.relay },
                    { !it.local },
                ),
            )

    fun routes(primaryUrl: String): List<ServerRoute> {
        val ordered =
            listOf(primaryUrl) +
                rankedConnections().map { it.uri }.filterNot { it.trimEnd('/') == primaryUrl.trimEnd('/') }
        return ordered
            .mapIndexed { index, url ->
                ServerRoute(
                    id = if (index == 0) ServerRoute.PRIMARY_ID else "plex${index + 1}",
                    name =
                        if (index == 0) {
                            ServerRoute.PRIMARY_NAME
                        } else if (rankedConnections().firstOrNull { it.uri == url }?.relay == true) {
                            "Plex Relay"
                        } else {
                            "Plex 线路 ${index + 1}"
                        },
                    url = url,
                )
            }.normalizedRoutes()
    }
}

@Serializable
private data class PlexPinDto(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    val expiresIn: Int = 0,
)

@Serializable
private data class PlexHomeUserDto(
    val uuid: String? = null,
    val id: Long? = null,
    val title: String? = null,
    val username: String? = null,
    val friendlyName: String? = null,
    @SerialName("protected") val isProtected: Boolean = false,
    val admin: Boolean = false,
    val authToken: String? = null,
)

@Serializable
private data class PlexConnectionDto(
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
)

@Serializable
private data class PlexResourceDto(
    val name: String = "Plex",
    val product: String = "",
    val clientIdentifier: String = "",
    val owned: Boolean = false,
    val accessToken: String? = null,
    val provides: String = "",
    val connections: List<PlexConnectionDto> = emptyList(),
)

/** plex.tv account boundary. Server-scoped calls stay in [PlexMediaServerAdapter]. */
internal class PlexCloudAccountService(
    private val client: HttpClient,
) {
    suspend fun startPin(nowEpochMs: Long): Result<PlexPinSession> =
        embyApiCall("plex_pin_start") {
            val pin =
                client
                    .post("$PLEX_CLOUD_ORIGIN/api/v2/pins") {
                        cloudHeaders()
                        parameter("strong", true)
                    }.body<PlexPinDto>()
            require(pin.code.isNotBlank() && pin.expiresIn > 0) { "Plex 未返回有效登录码" }
            val clientId = deviceId().encodeURLParameter()
            val code = pin.code.encodeURLParameter()
            val product = PLEX_CLOUD_PRODUCT.encodeURLParameter()
            PlexPinSession(
                id = pin.id,
                code = pin.code,
                authUrl =
                    "$PLEX_AUTH_ORIGIN/auth#?clientID=$clientId&code=$code&" +
                        "context%5Bdevice%5D%5Bproduct%5D=$product",
                expiresAtEpochMs = nowEpochMs + pin.expiresIn * 1_000L,
            )
        }

    suspend fun pollPin(
        session: PlexPinSession,
        nowEpochMs: Long,
    ): Result<PlexPinPoll> =
        if (nowEpochMs >= session.expiresAtEpochMs) {
            Result.success(PlexPinPoll(accessToken = null, expired = true))
        } else {
            embyApiCall("plex_pin_poll") {
                val pin =
                    client
                        .get("$PLEX_CLOUD_ORIGIN/api/v2/pins/${session.id}") {
                            cloudHeaders()
                            parameter("code", session.code)
                        }.body<PlexPinDto>()
                PlexPinPoll(
                    accessToken = pin.authToken?.takeIf(String::isNotBlank),
                    expired = false,
                )
            }
        }

    suspend fun homeUsers(accountToken: String): Result<List<PlexHomeUser>> =
        embyApiCall("plex_home_users") {
            client
                .get("$PLEX_CLOUD_ORIGIN/api/v2/home/users") {
                    cloudHeaders(accountToken)
                }.body<List<PlexHomeUserDto>>()
                .mapNotNull { user ->
                    val id = user.uuid ?: user.id?.toString() ?: return@mapNotNull null
                    PlexHomeUser(
                        id = id,
                        name = user.friendlyName ?: user.title ?: user.username ?: "Plex 用户",
                        pinProtected = user.isProtected,
                        admin = user.admin,
                    )
                }
        }

    suspend fun switchHomeUser(
        accountToken: String,
        userId: String,
        pin: String,
    ): Result<String> =
        embyApiCall("plex_home_switch") {
            require(userId.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Plex Home 用户标识无效" }
            require(pin.isEmpty() || pin.matches(Regex("[0-9]{4}"))) { "请输入 4 位 Plex Home PIN" }
            val switched =
                client
                    .post("$PLEX_CLOUD_ORIGIN/api/v2/home/users/$userId/switch") {
                        cloudHeaders(accountToken)
                        if (pin.isNotEmpty()) parameter("pin", pin)
                    }.body<PlexHomeUserDto>()
            requireNotNull(switched.authToken?.takeIf(String::isNotBlank)) { "Plex 未返回切换后的账号令牌" }
        }

    suspend fun resources(accountToken: String): Result<List<PlexCloudResource>> =
        embyApiCall("plex_cloud_resources") {
            client
                .get("$PLEX_CLOUD_ORIGIN/api/v2/resources") {
                    cloudHeaders(accountToken)
                    parameter("includeHttps", 1)
                    parameter("includeRelay", 1)
                    parameter("includeIPv6", 1)
                }.body<List<PlexResourceDto>>()
                .filter { resource ->
                    resource.product == "Plex Media Server" ||
                        resource.provides.split(',').any { it.trim() == "server" }
                }.mapNotNull { resource ->
                    resource.clientIdentifier.takeIf(String::isNotBlank)?.let { id ->
                        PlexCloudResource(
                            id = id,
                            name = resource.name.takeIf(String::isNotBlank) ?: "Plex",
                            owned = resource.owned,
                            accessToken = resource.accessToken?.takeIf(String::isNotBlank),
                            connections =
                                resource.connections.mapNotNull { connection ->
                                    ServerRoute.sanitizeUrl(connection.uri)?.let { uri ->
                                        PlexCloudConnection(uri, connection.local, connection.relay)
                                    }
                                },
                        )
                    }
                }.filter { it.connections.isNotEmpty() }
        }

    suspend fun setWatchlist(
        accountToken: String,
        cloudRatingKey: String,
        inWatchlist: Boolean,
    ): Result<Unit> =
        embyApiCall("plex_cloud_watchlist_write") {
            require(cloudRatingKey.matches(Regex("[A-Za-z0-9._-]{1,256}"))) { "Plex 云媒体标识无效" }
            val action = if (inWatchlist) "addToWatchlist" else "removeFromWatchlist"
            if (inWatchlist) {
                client.put("$PLEX_METADATA_ORIGIN/actions/$action") {
                    cloudHeaders(accountToken)
                    parameter("ratingKey", cloudRatingKey)
                }
            } else {
                client.delete("$PLEX_METADATA_ORIGIN/actions/$action") {
                    cloudHeaders(accountToken)
                    parameter("ratingKey", cloudRatingKey)
                }
            }
        }

    suspend fun isInWatchlist(
        accountToken: String,
        cloudRatingKey: String,
    ): Result<Boolean> =
        embyApiCall("plex_cloud_watchlist_read") {
            val container =
                client
                    .get("$PLEX_METADATA_ORIGIN/library/sections/watchlist/all") {
                        cloudHeaders(accountToken)
                        parameter("includeGuids", 1)
                    }.body<PlexResponseDto>()
                    .MediaContainer
            (container.Metadata + container.Directory).any { metadata ->
                metadata.ratingKey == cloudRatingKey ||
                    (metadata.Guid.map { it.id } + listOfNotNull(metadata.guid)).any {
                        it.substringAfterLast('/').substringBefore('?') == cloudRatingKey
                    }
            }
        }

    private fun HttpRequestBuilder.cloudHeaders(token: String? = null) {
        suppressEmbyIdentity()
        headers.remove("X-Emby-Authorization")
        headers.remove("X-Emby-Client")
        headers.remove("X-Emby-Client-Version")
        headers.remove("X-Emby-Device-Id")
        headers.remove("X-Emby-Device-Name")
        accept(ContentType.Application.Json)
        header("X-Plex-Client-Identifier", deviceId())
        header("X-Plex-Product", PLEX_CLOUD_PRODUCT)
        header("X-Plex-Platform", PLEX_CLOUD_PLATFORM)
        header("X-Plex-Device-Name", PLEX_CLOUD_PRODUCT)
        token?.let { header("X-Plex-Token", it) }
    }
}
