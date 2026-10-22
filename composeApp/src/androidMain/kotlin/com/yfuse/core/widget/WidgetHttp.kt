package com.yfuse.core.widget

import com.yfuse.core.network.tmdbToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A private client: no media-server authentication, cookie jar or account interceptors. */
internal object WidgetHttp {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val tmdbClient =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    suspend fun request(
        options: JsonObject,
        tmdb: Boolean = false,
    ): String {
        val rawUrl = options.text("url")
        val url =
            if (tmdb) {
                require(
                    rawUrl.startsWith("/") && !rawUrl.startsWith("//") && !rawUrl.contains("://"),
                ) { "TMDB 请求必须使用 API 相对路径" }
                "https://api.themoviedb.org/3$rawUrl".toHttpUrl()
            } else {
                rawUrl.toHttpUrl()
            }
        require(url.username.isBlank() && url.password.isBlank()) { "请使用请求头传递认证信息" }
        val builder = url.newBuilder()
        (options["params"] as? JsonObject)?.forEach { (key, value) ->
            if (value !=
                JsonNull
            ) {
                builder.addQueryParameter(key, (value as? JsonPrimitive)?.content ?: value.toString())
            }
        }
        val method = options.text("method").ifBlank { "GET" }.uppercase()
        require(method in setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")) { "不支持的 HTTP 方法" }
        val request = Request.Builder().url(builder.build()).header("User-Agent", "Yfuse-Widget/1")
        (options["headers"] as? JsonObject)?.forEach { (name, value) ->
            require(name.lowercase() !in setOf("host", "connection", "content-length")) { "不支持此请求头" }
            request.header(name, value.jsonPrimitive.content)
        }
        if (tmdb) {
            val apiKey = options.text("apiKey")
            if (apiKey.isNotBlank()) {
                request.url(builder.addQueryParameter("api_key", apiKey).build())
            } else {
                request.header("Authorization", "Bearer ${tmdbToken()}")
            }
        }
        val body = options["data"]
        val payload =
            if (method in setOf("GET", "HEAD")) {
                null
            } else {
                val encoded = (body as? JsonPrimitive)?.takeIf { it.isString }?.content ?: body?.toString().orEmpty()
                require(encoded.length <= MAX_WIDGET_SOURCE_BYTES) { "请求内容过大" }
                encoded.toRequestBody(
                    (request.build().header("Content-Type") ?: "application/json; charset=utf-8").toMediaType(),
                )
            }
        val call = (if (tmdb) tmdbClient else client).newCall(request.method(method, payload).build())
        call.timeout().timeout(
            options.text("timeout").toLongOrNull()?.coerceIn(1000, 30000) ?: 30000,
            TimeUnit.MILLISECONDS,
        )
        return awaitBody(call, 4 * MAX_WIDGET_SOURCE_BYTES) { response, text ->
            buildJsonObject {
                put("ok", response.isSuccessful)
                put("status", response.code)
                put("data", runCatching { widgetJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) })
                put(
                    "headers",
                    JsonObject(response.headers.toMultimap().mapValues { JsonArray(it.value.map(::JsonPrimitive)) }),
                )
            }.toString()
        }
    }

    suspend fun source(url: String): String {
        val parsed = url.toHttpUrl()
        require(parsed.username.isBlank() && parsed.password.isBlank())
        return awaitBody(
            client.newCall(Request.Builder().url(parsed).build()),
            MAX_WIDGET_SOURCE_BYTES,
        ) { response, body ->
            require(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
            body
        }
    }

    private suspend fun awaitBody(
        call: Call,
        limit: Int,
        transform: (Response, String) -> String,
    ): String =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            val result =
                                response.use {
                                    val body = it.body ?: error("响应内容为空")
                                    require(body.contentLength() <= limit) { "响应内容过大" }
                                    val bytes = body.byteStream().readBytesBounded(limit)
                                    transform(it, bytes.toString(Charsets.UTF_8))
                                }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                },
            )
        }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "文件内容过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
