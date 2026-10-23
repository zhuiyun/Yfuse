package com.yfuse.core.widget

import com.yfuse.core.network.TMDB_BASE
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reuses Yfuse's TMDB account routing without exposing its credentials or headers to JavaScript. */
internal class WidgetTmdbHttp(
    private val client: HttpClient,
) {
    suspend fun request(options: JsonObject): String {
        val path = options.text("url")
        require(CATALOG_PATH.matches(path) && !path.contains("//")) { "TMDB 请求必须使用公开资料的相对路径" }
        require(options.text("method").ifBlank { "GET" }.uppercase() == "GET") { "TMDB 组件仅支持读取资料" }
        val params = options["params"] as? JsonObject ?: JsonObject(emptyMap())
        require(params.all { (name, value) -> name.lowercase() !in AUTH_PARAMS && value is JsonPrimitive }) {
            "TMDB 组件参数不支持认证信息或嵌套数据"
        }
        val timeoutMillis = options.text("timeout").toLongOrNull()?.coerceIn(1000, 30000) ?: 30000
        try {
            return withTimeoutOrNull(timeoutMillis) {
                client
                    .prepareGet("$TMDB_BASE$path") {
                        expectSuccess = false
                        timeout {
                            requestTimeoutMillis = timeoutMillis
                        }
                        params.forEach { (name, value) ->
                            if (value !=
                                JsonNull
                            ) {
                                parameter(name, (value as JsonPrimitive).content)
                            }
                        }
                    }.execute { response ->
                        val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1L).readByteArray()
                        require(bytes.size <= MAX_RESPONSE_BYTES) { "TMDB 响应过大" }
                        val body = bytes.decodeToString()
                        buildJsonObject {
                            put("ok", response.status.isSuccess())
                            put("status", response.status.value)
                            put(
                                "data",
                                runCatching { widgetJson.parseToJsonElement(body) }.getOrElse { JsonPrimitive(body) },
                            )
                            put("headers", JsonObject(emptyMap()))
                        }.toString()
                    }
            } ?: throw WidgetRequestException("TMDB 服务响应超时，请稍后重试")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network exceptions can contain proxy URLs. Keep account details outside the script sandbox.
            throw if (e is WidgetRequestException) e else WidgetRequestException(e.widgetNetworkMessage(tmdb = true))
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * MAX_WIDGET_SOURCE_BYTES
        val CATALOG_PATH =
            Regex(
                "/(movie|tv|trending|discover|search|genre|configuration|network|company|collection|" +
                    "person|find|watch)" +
                    "(/[A-Za-z0-9_-]+)*",
            )
        val AUTH_PARAMS =
            setOf(
                "api_key",
                "apikey",
                "token",
                "access_token",
                "request_token",
                "session_id",
                "guest_session_id",
                "authorization",
            )
    }
}
