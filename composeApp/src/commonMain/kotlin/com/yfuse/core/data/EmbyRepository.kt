package com.yfuse.core.data

import com.yfuse.core.data.dto.AuthRequestDto
import com.yfuse.core.data.dto.AuthResultDto
import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.data.dto.ViewsDto
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.PlayTarget
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.normalizeBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Result of a successful authentication, ready to persist as a [SavedServer]. */
data class AuthedServer(
    val baseUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
) {
    fun toSavedServer() = SavedServer(
        id = SavedServer.idOf(baseUrl, userId),
        baseUrl = baseUrl,
        serverName = serverName,
        userId = userId,
        userName = userName,
        accessToken = accessToken,
    )
}

/**
 * Talks to Emby. Stateless with respect to sessions: every call targets an
 * explicit server. Failures carry an [EmbyErrorException].
 */
class EmbyRepository(private val client: HttpClient) {

    suspend fun authenticate(baseUrl: String, username: String, password: String): Result<AuthedServer> = call {
        val url = normalizeBaseUrl(baseUrl)
        val auth: AuthResultDto = client.post("$url/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequestDto(Username = username, Pw = password))
        }.body()
        val serverName = runCatching {
            client.get("$url/System/Info/Public").body<PublicInfoDto>().ServerName
        }.getOrNull()
        AuthedServer(url, serverName ?: url, auth.User.Id, auth.User.Name, auth.AccessToken)
    }

    suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> = call { fetchViews(server) }

    /** Aggregates the home screen: continue-watching, latest-per-library, featured. */
    suspend fun homeContent(server: SavedServer): Result<HomeContent> = call {
        coroutineScope {
            val views = fetchViews(server)
            // A single library (or the resume row) failing must not blank the
            // whole home screen — degrade to an empty row instead.
            val resumeDeferred = async { runCatching { fetchResume(server) }.getOrDefault(emptyList()) }
            val rowDeferred = views.map { view ->
                async {
                    val items = runCatching { fetchLatest(server, view.id) }.getOrDefault(emptyList())
                    HomeRow(view.id, view.name, items)
                }
            }
            val resume = resumeDeferred.await()
            val rows = rowDeferred.awaitAll().filter { it.items.isNotEmpty() }
            val featured = (resume + rows.flatMap { it.items })
                .filter { it.backdropTag != null }
                .distinctBy { it.id }
                .take(6)
            HomeContent(featured = featured, resume = resume, rows = rows)
        }
    }

    /** All movies/series in a library, for the "see all" grid. */
    suspend fun libraryItems(server: SavedServer, libraryId: String): Result<List<MediaItem>> = call {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items") {
            header("X-Emby-Token", server.accessToken)
            parameter("ParentId", libraryId)
            parameter("Recursive", true)
            parameter("IncludeItemTypes", "Movie,Series")
            parameter("SortBy", "SortName")
            parameter("SortOrder", "Ascending")
            parameter("Fields", "ProductionYear")
            parameter("EnableImageTypes", "Primary,Backdrop")
            parameter("ImageTypeLimit", 2)
            parameter("Limit", 120)
        }.body()
        dto.Items.map { it.toMediaItem() }
    }

    /**
     * Resolves what to actually play for a detail item: movies/episodes play
     * themselves; a series plays its "next up" episode (falling back to the
     * first episode), carrying that episode's resume position.
     */
    suspend fun resolvePlayTarget(server: SavedServer, detail: MediaDetail): Result<PlayTarget> = call {
        if (detail.type != "Series") {
            PlayTarget(detail.id, detail.resumePositionTicks ?: 0L)
        } else {
            val episode = fetchNextUp(server, detail.id) ?: fetchFirstEpisode(server, detail.id)
            requireNotNull(episode) { "no episodes" }
            PlayTarget(episode.Id, episode.UserData?.PlaybackPositionTicks ?: 0L)
        }
    }

    private suspend fun fetchNextUp(server: SavedServer, seriesId: String): BaseItemDto? {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Shows/NextUp") {
            header("X-Emby-Token", server.accessToken)
            parameter("UserId", server.userId)
            parameter("SeriesId", seriesId)
            parameter("Limit", 1)
        }.body()
        return dto.Items.firstOrNull()
    }

    private suspend fun fetchFirstEpisode(server: SavedServer, seriesId: String): BaseItemDto? {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Shows/$seriesId/Episodes") {
            header("X-Emby-Token", server.accessToken)
            parameter("UserId", server.userId)
            parameter("Limit", 1)
        }.body()
        return dto.Items.firstOrNull()
    }

    /** Full detail for a single item. */
    suspend fun itemDetail(server: SavedServer, itemId: String): Result<MediaDetail> = call {
        val dto: BaseItemDto = client.get("${server.baseUrl}/Users/${server.userId}/Items/$itemId") {
            header("X-Emby-Token", server.accessToken)
            parameter("Fields", "Overview,Genres,People")
        }.body()
        dto.toMediaDetail()
    }

    private suspend fun fetchViews(server: SavedServer): List<MediaLibrary> {
        val dto: ViewsDto = client.get("${server.baseUrl}/Users/${server.userId}/Views") {
            header("X-Emby-Token", server.accessToken)
        }.body()
        return dto.Items.map { MediaLibrary(it.Id, it.Name, it.CollectionType) }
    }

    private suspend fun fetchResume(server: SavedServer): List<MediaItem> {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items/Resume") {
            header("X-Emby-Token", server.accessToken)
            parameter("Limit", 12)
            parameter("Recursive", true)
            parameter("MediaTypes", "Video")
            parameter("Fields", "BackdropImageTags")
            parameter("EnableImageTypes", "Primary,Backdrop")
            parameter("ImageTypeLimit", 2)
        }.body()
        return dto.Items.map { it.toMediaItem() }
    }

    private suspend fun fetchLatest(server: SavedServer, viewId: String): List<MediaItem> {
        val items: List<BaseItemDto> = client.get("${server.baseUrl}/Users/${server.userId}/Items/Latest") {
            header("X-Emby-Token", server.accessToken)
            parameter("ParentId", viewId)
            parameter("Limit", 16)
            parameter("Fields", "BackdropImageTags,ProductionYear")
            parameter("EnableImageTypes", "Primary,Backdrop")
            parameter("ImageTypeLimit", 2)
        }.body()
        return items.map { it.toMediaItem() }
    }

    private inline fun <T> call(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: Throwable) {
            Result.failure(EmbyErrorException(e.toEmbyError()))
        }

    private fun Throwable.toEmbyError(): EmbyError = when (this) {
        is ResponseException -> when (response.status.value) {
            401 -> EmbyError.Unauthorized
            in 500..599 -> EmbyError.Server(response.status.value)
            else -> EmbyError.Unknown("HTTP ${response.status.value}")
        }
        else -> EmbyError.Network
    }
}
