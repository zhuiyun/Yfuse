package com.yfuse.core.data

import com.yfuse.core.data.dto.AuthRequestDto
import com.yfuse.core.data.dto.AuthResultDto
import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.data.dto.ViewsDto
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.User
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Single data entry point for Emby. All methods return [Result]; failures carry
 * an [EmbyErrorException] so the presentation layer can map them to messages.
 */
class EmbyRepository(
    private val client: HttpClient,
    private val session: SessionManager,
) {

    /** Verifies a server is reachable; returns its display name. */
    suspend fun checkServer(baseUrl: String): Result<String> = call {
        val info: PublicInfoDto = client.get("${baseUrl.trimEnd('/')}/System/Info/Public").body()
        info.ServerName ?: "Emby"
    }

    /** Authenticates and stores the session on success. */
    suspend fun login(baseUrl: String, username: String, password: String): Result<User> = call {
        val url = baseUrl.trimEnd('/')
        val result: AuthResultDto = client.post("$url/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequestDto(Username = username, Pw = password))
        }.body()
        session.save(url, result.AccessToken, result.User.Id)
        User(result.User.Id, result.User.Name, result.AccessToken)
    }

    /** Lists the current user's libraries ("views"). Requires an active session. */
    suspend fun libraries(): Result<List<MediaLibrary>> = call {
        val base = session.baseUrl()
        val uid = session.userId()
        val dto: ViewsDto = client.get("$base/Users/$uid/Views").body()
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
