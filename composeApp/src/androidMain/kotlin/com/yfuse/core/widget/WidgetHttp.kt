package com.yfuse.core.widget

import com.yfuse.core.network.tmdbToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A private client: no media-server authentication, cookie jar or account interceptors. */
internal object WidgetHttp {
    private val client =
        OkHttpClient
            .Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            // WidgetHttp owns the retry budget, including connection failures and HTTP 408.
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                // Each Call owns its metadata, including the explicit recovery Call.
                val retryControl = WidgetRetryControl()
                val response =
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .tag(WidgetRetryControl::class.java, retryControl)
                            .build(),
                    )
                val retryAfter = retryControl.retryAfter
                if (response.code == 503 && retryAfter != null) {
                    response
                        .newBuilder()
                        .removeHeader("Retry-After")
                        .apply {
                            retryAfter.forEach { addHeader("Retry-After", it) }
                        }.build()
                } else {
                    response
                }
            }.addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503) {
                    // OkHttp follows 503 + Retry-After: 0 even when connection retries are off.
                    // Suppress that hidden replay; restore the real header outside its interceptor.
                    checkNotNull(chain.request().tag(WidgetRetryControl::class.java)).retryAfter =
                        response.headers.values("Retry-After")
                    response.newBuilder().header("Retry-After", "1").build()
                } else {
                    response
                }
            }.build()

    private class WidgetRetryControl {
        var retryAfter: List<String>? = null
    }

    private val tmdbClient =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    // A failed multiplexed/pooled read gets one fresh HTTP/1.1 route, to the same origin.
    private val recoveryClient =
        client
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool())
            .retryOnConnectionFailure(false)
            .build()
    private val tmdbRecoveryClient =
        recoveryClient
            .newBuilder()
            .followRedirects(
                false,
            ).followSslRedirects(false)
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
                SingleUseWidgetBody(
                    encoded.toRequestBody(
                        (request.build().header("Content-Type") ?: "application/json; charset=utf-8").toMediaType(),
                    ),
                )
            }
        val timeoutMillis = options.text("timeout").toLongOrNull()?.coerceIn(1000, 30000) ?: 30000
        return requestWithRecovery(request.method(method, payload).build(), timeoutMillis, tmdb) { response, text ->
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

    /** At most one application-level read recovery; writes never enter that recovery path. */
    private suspend fun requestWithRecovery(
        request: Request,
        timeoutMillis: Long,
        tmdb: Boolean,
        transform: (Response, String) -> String,
    ): String {
        val readOnly = request.method in setOf("GET", "HEAD")
        val started = System.nanoTime()

        fun remaining() = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return withTimeoutOrNull(timeoutMillis) {
            var retry = false
            while (true) {
                val selectedClient =
                    when {
                        tmdb && retry -> tmdbRecoveryClient
                        tmdb -> tmdbClient
                        retry -> recoveryClient
                        else -> client
                    }
                val call = selectedClient.newCall(request)
                call.timeout().timeout(remaining().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                try {
                    val result =
                        awaitBody(call, 4 * MAX_WIDGET_SOURCE_BYTES) { response, text ->
                            // Only gateway/service failures are transient. Authentication and 4xx stay untouched.
                            if (readOnly && !retry && response.code in setOf(502, 503, 504) && remaining() > 500) {
                                throw RetryWidgetRead()
                            }
                            transform(response, text)
                        }
                    return@withTimeoutOrNull result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (!readOnly ||
                        retry ||
                        remaining() <= 500 ||
                        (e !is RetryWidgetRead && !e.isRetryableWidgetReadFailure())
                    ) {
                        throw WidgetRequestException(e.widgetNetworkMessage(tmdb))
                    }
                    retry = true
                    delay(150)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        } ?: throw WidgetRequestException(if (tmdb) "TMDB 服务响应超时，请稍后重试" else "内容服务响应超时，请稍后重试")
    }

    // Even HTTP/2's 421 connection recovery must not replay a widget mutation.
    private class SingleUseWidgetBody(
        private val delegate: RequestBody,
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun isOneShot() = true

        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }

    private class RetryWidgetRead : IOException()

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
