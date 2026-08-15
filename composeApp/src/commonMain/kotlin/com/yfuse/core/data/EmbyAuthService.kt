package com.yfuse.core.data

import com.yfuse.core.data.dto.AuthRequestDto
import com.yfuse.core.data.dto.AuthResultDto
import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.normalizeBaseUrl
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
            client.get("${normalizeBaseUrl(baseUrl)}/Users/Public").body()
        }

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<AuthedServer> =
        embyApiCall("authenticate") {
            val url = normalizeBaseUrl(baseUrl)
            val auth: AuthResultDto =
                client
                    .post("$url/Users/AuthenticateByName") {
                        contentType(ContentType.Application.Json)
                        setBody(AuthRequestDto(Username = username, Pw = password))
                    }.body()
            val serverInfo =
                runCatching {
                    client.get("$url/System/Info/Public").body<PublicInfoDto>().ServerName
                }.onFailure {
                    AppLog.warning(
                        category = "emby",
                        event = "server_info_degraded",
                        message = "Authentication succeeded but public server info failed",
                        throwable = it,
                    )
                }
            AuthedServer(url, serverInfo.getOrNull() ?: url, auth.User.Id, auth.User.Name, auth.AccessToken)
        }
}
