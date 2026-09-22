package com.yfuse.core.data

import com.yfuse.core.data.dto.RemoteSubtitleInfoDto
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.normalizeBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.encodeURLPathPart

/** Remote subtitle discovery and installation. */
internal class EmbySubtitleService(
    private val client: HttpClient,
) {
    suspend fun search(
        server: SavedServer,
        itemId: String,
        language: String,
    ): Result<List<RemoteSubtitleInfoDto>> =
        embyApiCall("remote_subtitle_search") {
            client
                .get(
                    "${normalizeBaseUrl(server.baseUrl)}/Items/$itemId/RemoteSearch/Subtitles/" +
                        language.encodeURLPathPart(),
                ) {
                    header("X-Emby-Token", server.accessToken)
                    parameter("IsPerfectMatch", false)
                }.body()
        }

    suspend fun download(
        server: SavedServer,
        itemId: String,
        subtitleId: String,
    ): Result<Unit> =
        embyApiCall("remote_subtitle_download") {
            client.post(
                "${normalizeBaseUrl(server.baseUrl)}/Items/$itemId/RemoteSearch/Subtitles/" +
                    subtitleId.encodeURLPathPart(),
            ) {
                header("X-Emby-Token", server.accessToken)
            }
        }
}
