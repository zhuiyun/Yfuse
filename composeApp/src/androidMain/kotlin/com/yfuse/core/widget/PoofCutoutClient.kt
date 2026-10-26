package com.yfuse.core.widget

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PoofApiException(
    val status: Int,
    val retryAfterMillis: Long? = null,
) : IOException("Poof HTTP $status")

/** An isolated client never sends media-server URLs, cookies or authentication to Poof. */
internal class PoofCutoutClient(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build(),
    private val endpoint: HttpUrl = "https://api.poof.bg/v1/remove".toHttpUrl(),
) {
    suspend fun remove(
        apiKey: String,
        sourcePng: ByteArray,
    ): ByteArray {
        require(apiKey.isNotBlank() && apiKey.length <= 256 && apiKey.none(Char::isISOControl))
        require(sourcePng.size in 1..MAX_POOF_IMAGE_BYTES)
        val multipart =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image_file", "poster.png", sourcePng.toRequestBody("image/png".toMediaType()))
                .build()
        // A billable POST must not be replayed by OkHttp's 421/503 follow-up handling.
        val singleUse =
            object : RequestBody() {
                override fun contentType() = multipart.contentType()

                override fun contentLength() = multipart.contentLength()

                override fun isOneShot() = true

                override fun writeTo(sink: BufferedSink) = multipart.writeTo(sink)
            }
        val request =
            Request
                .Builder()
                .url(endpoint)
                .header("x-api-key", apiKey)
                .header("Accept", "image/png")
                .post(singleUse)
                .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
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
                            val bytes =
                                response.use {
                                    if (!it.isSuccessful) {
                                        throw PoofApiException(it.code, poofRetryAfterMillis(it.header("Retry-After")))
                                    }
                                    val body = it.body ?: throw IOException("Empty Poof response")
                                    check(body.contentLength() <= MAX_POOF_IMAGE_BYTES)
                                    check(
                                        body.contentType()?.let { type ->
                                            type.type == "image" && type.subtype == "png"
                                        } ==
                                            true,
                                    )
                                    body.byteStream().readBytesBounded(MAX_POOF_IMAGE_BYTES).also { image ->
                                        check(image.size >= 8 && image.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
                                    }
                                }
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                },
            )
        }
    }
}

internal fun poofRetryAfterMillis(
    value: String?,
    now: Long = System.currentTimeMillis(),
): Long? {
    val text = value?.trim() ?: return null
    val seconds = text.toLongOrNull()
    if (seconds != null) return seconds.coerceIn(0, 86_400) * 1000
    return runCatching {
        (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
            .coerceIn(0, 86_400_000)
    }.getOrNull()
}

internal const val MAX_POOF_IMAGE_BYTES = 8 * 1024 * 1024
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
