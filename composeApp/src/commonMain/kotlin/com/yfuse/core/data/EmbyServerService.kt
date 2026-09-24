package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.exemptFromServerCooldown
import com.yfuse.core.network.normalizeBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlin.time.TimeSource

/** Cheap authenticated reachability and latency probes for saved server routes. */
internal class EmbyServerService(
    private val client: HttpClient,
) {
    suspend fun probe(server: SavedServer): Result<Long> = probeAddress(server.baseUrl, server.accessToken)

    suspend fun probeAddress(
        baseUrl: String,
        accessToken: String,
    ): Result<Long> =
        embyApiCall("server_probe") {
            val mark = TimeSource.Monotonic.markNow()
            client
                .get("${normalizeBaseUrl(baseUrl)}/System/Info") {
                    // The probe is how a cooled-down server is found to be back; it must reach it.
                    exemptFromServerCooldown()
                    header("X-Emby-Token", accessToken)
                }.bodyAsText()
            mark.elapsedNow().inWholeMilliseconds
        }
}
