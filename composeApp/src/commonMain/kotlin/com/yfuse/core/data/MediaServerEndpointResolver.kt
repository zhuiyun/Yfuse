package com.yfuse.core.data

import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.normalizeBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException

/** Repair obsolete Jellyfin aliases only after a public probe confirms the replacement.
 * Never strip a working reverse-proxy base path, and never send passwords during probing.
 */
internal suspend fun HttpClient.resolveMediaServerBaseUrl(raw: String): String {
    val original = normalizeBaseUrl(raw)
    if (original.substringAfterLast('/').lowercase() !in setOf("emby", "mediabrowser")) return original
    try {
        get("$original/System/Info/Public").body<PublicInfoDto>()
        return original
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ResponseException) {
        if (error.response.status.value !in setOf(404, 405)) return original
    } catch (_: Exception) {
        return original
    }
    val candidate = original.substringBeforeLast('/')
    return try {
        if (get("$candidate/System/Info/Public").body<PublicInfoDto>().mediaServerKind() ==
            MediaServerKind.Jellyfin
        ) {
            candidate
        } else {
            original
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        original
    }
}
