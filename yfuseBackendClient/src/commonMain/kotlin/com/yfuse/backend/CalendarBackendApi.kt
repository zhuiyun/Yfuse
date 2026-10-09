package com.yfuse.backend

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

interface CalendarBackendApi {
    val enabled: Boolean

    /** Null means an unchanged publication (304). Signature validation belongs to the consumer. */
    suspend fun fetch(revision: String): OfficialScheduleEnvelope?
}

class HttpCalendarBackendApi(
    private val client: HttpClient,
    private val endpoint: String = BackendEndpoints.CALENDAR,
    private val access: BackendAccess = BackendAccess.Default,
) : CalendarBackendApi {
    override val enabled: Boolean get() = access.enabled

    override suspend fun fetch(revision: String): OfficialScheduleEnvelope? {
        access.requireEnabled(BackendFeature.Calendar)
        return withTimeout(5_000L) {
            val response =
                client.get(endpoint) {
                    if (revision.isNotBlank()) header(HttpHeaders.IfNoneMatch, "\"calendar-$revision\"")
                }
            if (response.status == HttpStatusCode.NotModified) return@withTimeout null
            check(response.status.isSuccess()) { "Calendar request failed (${response.status.value})" }
            response.body<OfficialScheduleEnvelope>()
        }
    }
}

@Serializable
data class OfficialScheduleEnvelope(
    val schemaVersion: Int,
    val revision: String,
    val generatedAt: String,
    /** Exact JSON bytes (UTF-8) covered by signature. */
    val payload: String,
    val signature: String,
)
