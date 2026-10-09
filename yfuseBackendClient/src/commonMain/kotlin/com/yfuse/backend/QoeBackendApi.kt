package com.yfuse.backend

import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

interface QoeBackendApi {
    val enabled: Boolean

    suspend fun send(report: AnonymousPlaybackQoeReport): Boolean
}

class HttpQoeBackendApi(
    private val client: HttpClient,
    baseUrl: String = BackendEndpoints.ORIGIN,
    private val access: BackendAccess = BackendAccess.Default,
) : QoeBackendApi {
    override val enabled: Boolean get() = access.enabled
    private val endpoint =
        baseUrl.trimEnd('/').also {
            require(it.startsWith("https://")) { "QoE aggregation requires HTTPS" }
        } + "/api/v1/qoe"

    override suspend fun send(report: AnonymousPlaybackQoeReport): Boolean {
        access.requireEnabled(BackendFeature.Qoe)
        return client
            .post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(report)
            }.status in setOf(HttpStatusCode.Accepted, HttpStatusCode.OK, HttpStatusCode.NoContent)
    }
}
