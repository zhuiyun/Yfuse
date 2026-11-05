package com.yfuse.core.cloud

import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal suspend fun Call.cloudAwait(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
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
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }

internal suspend fun OkHttpClient.cloudJson(
    request: Request,
    allowOAuthError: Boolean = false,
): JsonObject =
    newCall(request).withCloudResponse { response ->
        if (!response.isSuccessful && !(allowOAuthError && response.code == 400)) {
            throw FileSourceException(
                if (response.code in listOf(401, 403)) FileSourceFailure.Unauthorized else FileSourceFailure.Server,
                status = response.code,
            )
        }
        val bytes = withContext(Dispatchers.IO) { response.cloudReadBounded(2 * 1024 * 1024) }
        try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (_: IllegalArgumentException) {
            throw FileSourceException(FileSourceFailure.InvalidResponse)
        }
    }

/** Keep cancellation connected after headers have arrived and the response body is still blocked. */
internal suspend fun <T> Call.withCloudResponse(block: suspend (Response) -> T): T {
    val callerContext = currentCoroutineContext()
    return try {
        coroutineScope {
            val cancelRead =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        this@withCloudResponse.cancel()
                    }
                }
            try {
                cloudAwait().use { block(it) }
            } finally {
                cancelRead.cancel()
            }
        }
    } catch (error: IOException) {
        // Closing a blocked socket may win the exception race. Preserve caller cancellation.
        callerContext.ensureActive()
        throw error
    }
}

internal fun Response.cloudReadBounded(maximumBytes: Int): ByteArray {
    val stream = body.source()
    if (body.contentLength() > maximumBytes || stream.request(maximumBytes.toLong() + 1)) {
        throw FileSourceException(FileSourceFailure.TooLarge)
    }
    return stream.readByteArray()
}

internal fun JsonObject.text(name: String): String = get(name)?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun JsonObject.number(name: String): Long = text(name).toLongOrNull() ?: 0L
