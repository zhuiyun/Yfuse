package com.yfuse.feature

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
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

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** Builds a repository whose HTTP calls are served by [handler]. */
fun testRepo(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): EmbyRepository = EmbyRepository(createEmbyClient(MockEngine(handler)))

/** A fresh in-memory server registry for tests. */
fun testRegistry(): ServerRegistry = ServerRegistry(MapSettings())

fun MockRequestHandleScope.json(body: String): HttpResponseData =
    respond(content = ByteReadChannel(body), status = HttpStatusCode.OK, headers = jsonHeaders)

/** Routes the two auth calls: AuthenticateByName and System/Info/Public. */
fun MockRequestHandleScope.authRoutes(
    request: HttpRequestData,
    authBody: String = """{"AccessToken":"tok","User":{"Id":"u1","Name":"zhuiyun"}}""",
    infoBody: String = """{"ServerName":"zhuiyun","Version":"4.9.1.90"}""",
): HttpResponseData = when {
    request.url.encodedPath.endsWith("AuthenticateByName") -> json(authBody)
    request.url.encodedPath.contains("Info/Public") -> json(infoBody)
    else -> json("{}")
}
