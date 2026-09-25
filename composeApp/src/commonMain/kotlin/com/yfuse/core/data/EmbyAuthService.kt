package com.yfuse.core.data

import com.yfuse.core.data.dto.AuthRequestDto
import com.yfuse.core.data.dto.AuthResultDto
import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Authentication and public-server discovery for an Emby-compatible endpoint. */
internal class EmbyAuthService(
    private val client: HttpClient,
) {
    suspend fun publicUsers(baseUrl: String): Result<List<PublicUserDto>> =
        embyApiCall("public_users") {
            client.get("${client.resolveMediaServerBaseUrl(baseUrl)}/Users/Public").body()
        }

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<AuthedServer> {
        var resolvedUrl: String? = null
        return embyApiCall("authenticate") {
            val url = client.resolveMediaServerBaseUrl(baseUrl).also { resolvedUrl = it }
            val auth: AuthResultDto =
                client
                    .post("$url/Users/AuthenticateByName") {
                        contentType(ContentType.Application.Json)
                        setBody(AuthRequestDto(Username = username, Pw = password))
                    }.body()
            val serverInfo =
                runCatchingCancellable {
                    client.get("$url/System/Info/Public").body<PublicInfoDto>()
                }.onFailure {
                    AppLog.warning(
                        category = "emby",
                        event = "server_info_degraded",
                        message = "Authentication succeeded but public server info failed",
                        throwable = it,
                    )
                }
            val publicInfo = serverInfo.getOrNull()
            val kind = publicInfo.mediaServerKind()
            AuthedServer(
                baseUrl = url,
                serverName = publicInfo?.ServerName ?: url,
                userId = auth.User.Id,
                userName = auth.User.Name,
                accessToken = auth.AccessToken,
                kind = kind,
            )
        }.orNotMediaServer(resolvedUrl)
    }

    /**
     * A sign-in answered with a 404, another status Emby would not send, or a body that is not
     * its JSON usually means the address is a web page or the path is wrong. Only this failure
     * path pays for one more request: when the public info endpoint does not answer as a media
     * server either, the user is told that instead of 「找不到该内容」 or a generic error.
     */
    private suspend fun Result<AuthedServer>.orNotMediaServer(url: String?): Result<AuthedServer> {
        val error = (exceptionOrNull() as? EmbyErrorException)?.error
        val unexpectedAnswer = error == EmbyError.NotFound || error is EmbyError.Unknown
        return if (url != null && unexpectedAnswer && !answersAsMediaServer(url)) {
            Result.failure(EmbyErrorException(EmbyError.NotMediaServer))
        } else {
            this
        }
    }

    /** Any JSON decodes into [PublicInfoDto]; a media server's also carries its id or version. */
    private suspend fun answersAsMediaServer(url: String): Boolean =
        offUiThread {
            runCatchingCancellable { client.get("$url/System/Info/Public").body<PublicInfoDto>() }
        }.getOrNull()?.let { !it.Id.isNullOrBlank() || !it.Version.isNullOrBlank() } == true
}

internal fun PublicInfoDto?.mediaServerKind(): MediaServerKind =
    if (this?.ProductName?.contains("Jellyfin", ignoreCase = true) == true) {
        MediaServerKind.Jellyfin
    } else {
        MediaServerKind.Emby
    }
