package com.yfuse.feature

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.network.createEmbyClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** Builds a repository whose HTTP calls are served by [handler]. */
fun testRepo(
    dispatcher: CoroutineDispatcher? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): EmbyRepository = EmbyRepository(
    createEmbyClient(
        appVersion = "test",
        engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher?.let { this.dispatcher = it }
                addHandler(handler)
            },
        ),
        timeouts = null,
    ),
)

/** A fresh in-memory server registry for tests. */
fun testRegistry(): ServerRegistry = ServerRegistry(MapSettings(), TestSecureStore())

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

/** Routes the home aggregation calls: Views, Items/Resume, Items/Latest. */
fun MockRequestHandleScope.homeRoutes(
    request: HttpRequestData,
    views: String = """{"Items":[{"Id":"lib1","Name":"电影-国产电影","CollectionType":"movies"}]}""",
    resume: String = """{"Items":[{"Id":"e1","Name":"第1集","Type":"Episode","SeriesName":"某剧",""" +
        """"SeriesId":"s1","SeriesPrimaryImageTag":"stag","ImageTags":{"Primary":"p"},""" +
        """"BackdropImageTags":[],"UserData":{"PlayedPercentage":30.0}}]}""",
    latest: String = """[{"Id":"m1","Name":"某电影","Type":"Movie","ProductionYear":2026,""" +
        """"ImageTags":{"Primary":"pt"},"BackdropImageTags":["bt"]}]""",
    movieCount: Int = 42,
    seriesCount: Int = 7,
): HttpResponseData = when {
    request.url.encodedPath.endsWith("/Views") -> json(views)
    request.url.encodedPath.endsWith("/Items/Counts") -> json(
        """{"MovieCount":$movieCount,"SeriesCount":$seriesCount}""",
    )
    request.url.encodedPath.contains("/Items/Resume") -> json(resume)
    request.url.encodedPath.contains("/Items/Latest") -> json(latest)
    else -> json("{}")
}
