package com.yfuse.core.data

import com.yfuse.core.data.dto.AuthRequestDto
import com.yfuse.core.data.dto.AuthResultDto
import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.data.dto.ViewsDto
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

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

    /** Authenticates against [baseUrl], also fetching the server's display name. */
    suspend fun authenticate(baseUrl: String, username: String, password: String): Result<AuthedServer> = call {
        val url = baseUrl.trim().trimEnd('/')
        val auth: AuthResultDto = client.post("$url/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequestDto(Username = username, Pw = password))
        }.body()
        val serverName = runCatching {
            client.get("$url/System/Info/Public").body<PublicInfoDto>().ServerName
        }.getOrNull()
        AuthedServer(
            baseUrl = url,
            serverName = serverName ?: url,
            userId = auth.User.Id,
            userName = auth.User.Name,
            accessToken = auth.AccessToken,
        )
    }

    /** Lists the libraries ("views") of a saved server. */
    suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> = call {
        val dto: ViewsDto = client.get("${server.baseUrl}/Users/${server.userId}/Views") {
            header("X-Emby-Token", server.accessToken)
        }.body()
        dto.Items.map { MediaLibrary(it.Id, it.Name, it.CollectionType) }
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
