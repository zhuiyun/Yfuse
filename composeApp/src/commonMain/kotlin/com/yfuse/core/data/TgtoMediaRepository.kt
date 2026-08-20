package com.yfuse.core.data

import com.yfuse.core.network.embyHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private const val TGTO_REQUEST_TIMEOUT_MS = 45_000L
private const val TGTO_CONNECT_TIMEOUT_MS = 12_000L

fun createTgtoMediaClient(engine: HttpClientEngine = embyHttpEngine()): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentEncoding) { gzip() }
        install(HttpCookies) { storage = AcceptAllCookiesStorage() }
        install(HttpTimeout) {
            requestTimeoutMillis = TGTO_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = TGTO_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = TGTO_REQUEST_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                },
            )
        }
    }

class TgtoApiException(
    message: String,
    val code: String? = null,
) : IllegalStateException(message)

class TgtoMediaRepository(
    private val preferences: TgtoMediaPreferences,
    private val client: HttpClient = createTgtoMediaClient(),
) {
    private val loginMutex = Mutex()
    private var authenticatedIdentity: String? = null
    private val pan123 = Pan123DirectClient(preferences, client)

    suspend fun testConnection(
        endpoint: String,
        username: String,
        password: String,
    ): Result<TgtoSettings> =
        runCatching {
            login(endpoint.trim().trimEnd('/'), username.trim(), password)
            request<TgtoSettings>(endpoint.trim().trimEnd('/')) { base -> client.get("$base/api/media/settings") }
        }

    suspend fun settings(): Result<TgtoSettings> = apiCall { base -> client.get("$base/api/media/settings") }

    suspend fun saveSettings(update: TgtoSettingsUpdate): Result<TgtoSettings> =
        apiCall { base ->
            client.put("$base/api/media/settings") {
                contentType(ContentType.Application.Json)
                setBody(update)
            }
        }

    suspend fun testEmby(update: TgtoEmbyUpdate): Result<TgtoEmbyTestResult> =
        apiCall { base ->
            client.post("$base/api/media/emby/test") {
                contentType(ContentType.Application.Json)
                setBody(TgtoEmbyTestRequest(update))
            }
        }

    suspend fun list123Directories(parentId: String = "0"): Result<TgtoDirectoryListing> =
        runCatching { pan123.listDirectories(parentId) }

    suspend fun authorizePan123(
        phone: String,
        password: String,
    ): Result<TgtoDirectoryListing> = runCatching { pan123.authorize(phone, password) }

    fun clearPan123Authorization() = pan123.clearAuthorization()

    suspend fun rankings(
        provider: String,
        mediaType: String,
        region: String = "US",
        page: Int = 1,
    ): Result<TgtoMediaPage> =
        apiCall { base ->
            client.get("$base/api/media/rankings/$provider") {
                parameter("media_type", mediaType)
                parameter("region", region)
                parameter("page", page)
            }
        }

    suspend fun discover(
        source: String,
        mediaType: String,
        page: Int = 1,
    ): Result<TgtoMediaPage> =
        apiCall { base ->
            client.get("$base/api/media/discover/$source") {
                parameter("media_type", mediaType)
                parameter("page", page)
            }
        }

    suspend fun search(
        query: String,
        mediaType: String,
        page: Int = 1,
    ): Result<TgtoMediaPage> =
        apiCall { base ->
            client.get("$base/api/media/search") {
                parameter("q", query)
                parameter("media_type", mediaType)
                parameter("page", page)
            }
        }

    suspend fun anime(
        source: String,
        query: String,
        page: Int = 1,
    ): Result<TgtoMediaPage> =
        apiCall { base ->
            client.get("$base/api/media/anime/$source") {
                parameter("page", page)
                if (query.isNotBlank()) parameter("q", query)
            }
        }

    suspend fun calendar(days: Int = 30): Result<TgtoMediaPage> =
        apiCall { base ->
            client.get("$base/api/media/calendar") { parameter("days", days.coerceIn(7, 60)) }
        }

    suspend fun details(item: TgtoMediaItem): Result<TgtoMediaItem> {
        val source = item.source.lowercase()
        val externalId = item.externalId.ifBlank { item.id.substringAfterLast(':') }
        return when {
            source == "anilist" || source == "bangumi" ->
                apiCall { base ->
                    client.get("$base/api/media/details/$source/anime/$externalId")
                }
            item.tmdbId != null ->
                apiCall { base ->
                    client.get("$base/api/media/details/tmdb/${item.normalizedMediaType}/${item.tmdbId}")
                }
            else -> Result.success(item)
        }
    }

    suspend fun embyCards(items: List<TgtoMediaItem>): Result<TgtoEmbyCardsResult> {
        val targets = items.mapNotNull(TgtoMediaItem::toEmbyCardTarget).distinctBy(TgtoEmbyCardTarget::key)
        if (targets.isEmpty()) return Result.success(TgtoEmbyCardsResult())
        return apiCall { base ->
            client.post("$base/api/media/emby/cards") {
                contentType(ContentType.Application.Json)
                setBody(TgtoEmbyCardsRequest(targets))
            }
        }
    }

    suspend fun search123Resources(
        item: TgtoMediaItem,
        settings: TgtoSettings,
    ): Result<TgtoResourceSearchResult> =
        apiCall { base ->
            val channels = settings.tgResourceChannels["123"].orEmpty().ifEmpty { DEFAULT_123_CHANNELS }
            client.post("$base/api/media/resources/search") {
                contentType(ContentType.Application.Json)
                setBody(
                    TgtoResourceSearchRequest(
                        title = item.title,
                        aliases = listOf(item.originalTitle).filter(String::isNotBlank).distinct().take(2),
                        tmdbId = item.tmdbId ?: item.externalId.toIntOrNull(),
                        mediaType = item.normalizedMediaType,
                        year = item.year.takeIf(String::isNotBlank),
                        season = item.calendarEpisode?.seasonNumber,
                        episode = item.calendarEpisode?.episodeNumber,
                        preferences =
                            TgtoResourcePreferences(
                                resolutions = settings.resolutions,
                                qualities = settings.qualities,
                                languages = settings.languages,
                                tgResourceChannels = mapOf("123" to channels),
                            ),
                    ),
                )
            }
        }

    suspend fun transferTo123(resource: TgtoResourceItem): Result<String> =
        runCatching {
            require(resource.provider.equals("123", true)) { "当前仅支持转存 123 云盘资源" }
            val target =
                settings()
                    .getOrThrow()
                    .mediaTransferTargets["123"]
                    ?.takeIf { it.configured && it.folderId.isNotBlank() }
                    ?: throw TgtoApiException("请先在设置中选择 123 保存目录")
            pan123.transfer(resource.shareUrl.ifBlank { resource.resourceUrl }, target.folderId)
        }

    private suspend inline fun <reified T> apiCall(crossinline block: suspend (String) -> HttpResponse): Result<T> =
        runCatching {
            val connection = preferences.connection.value
            require(connection.hasPassword) { "请先在设置中填写 TgtoDrive 密码" }
            request(connection.endpoint, block)
        }

    private suspend inline fun <reified T> request(
        endpoint: String,
        crossinline block: suspend (String) -> HttpResponse,
    ): T {
        val envelope = requestEnvelope<T>(endpoint, block)
        return envelope.data ?: throw TgtoApiException("TgtoDrive 返回了空数据")
    }

    private suspend inline fun <reified T> requestEnvelope(
        endpoint: String,
        crossinline block: suspend (String) -> HttpResponse,
    ): TgtoEnvelope<T> {
        val response = authenticatedResponse(endpoint, block)
        val envelope = response.body<TgtoEnvelope<T>>()
        if (response.status.value !in 200..299 || !envelope.success) {
            throw TgtoApiException(
                envelope.error ?: envelope.message ?: "TgtoDrive 请求失败（${response.status.value}）",
                envelope.code,
            )
        }
        return envelope
    }

    private suspend inline fun authenticatedResponse(
        endpoint: String,
        crossinline block: suspend (String) -> HttpResponse,
    ): HttpResponse {
        var response = block(endpoint)
        if (response.status == HttpStatusCode.Unauthorized) {
            loginStored(endpoint)
            response = block(endpoint)
        }
        return response
    }

    private suspend fun loginStored(endpoint: String) {
        val connection = preferences.connection.value
        val identity = "${connection.endpoint}\u0000${connection.username}"
        loginMutex.withLock {
            if (authenticatedIdentity == identity) authenticatedIdentity = null
            login(endpoint, connection.username, preferences.password())
        }
    }

    private suspend fun login(
        endpoint: String,
        username: String,
        password: String,
    ) {
        require(endpoint.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            "TgtoDrive 地址、账号和密码不能为空"
        }
        val response =
            client.post("$endpoint/api/login") {
                contentType(ContentType.Application.Json)
                setBody(TgtoLoginRequest(username, password))
            }
        val envelope = response.body<TgtoEnvelope<Unit>>()
        if (response.status.value !in 200..299 || !envelope.success) {
            throw TgtoApiException(
                envelope.error ?: envelope.message ?: "TgtoDrive 登录失败",
                envelope.code,
            )
        }
        authenticatedIdentity = "$endpoint\u0000$username"
    }
}
