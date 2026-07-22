package com.yfuse.feature

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.yfuse.core.network.createEmbyClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** Builds a repository whose HTTP calls are served by [handler], plus its session. */
fun testRepo(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<EmbyRepository, SessionManager> {
    val session = SessionManager(MapSettings())
    val client = createEmbyClient(MockEngine(handler)) { session.token() }
    return EmbyRepository(client, session) to session
}

fun MockRequestHandleScope.json(body: String): HttpResponseData =
    respond(content = ByteReadChannel(body), status = HttpStatusCode.OK, headers = jsonHeaders)
