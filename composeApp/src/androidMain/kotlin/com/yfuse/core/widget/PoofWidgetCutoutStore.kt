package com.yfuse.core.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.get
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import com.yfuse.core.network.imageCacheKeyForUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal const val POOF_CACHE_DIRECTORY = "widget-cutout-poof-v1"
private const val REQUEST_SPACING_MS = 16_000L
private const val DAY_MS = 24L * 60 * 60 * 1000

/** Only active cards request new work. Completed paid work survives a route/scroll cancellation. */
internal object PoofWidgetCutoutStore {
    private var instance: PoofWidgetCutoutPipeline? = null

    @Synchronized
    fun pipeline(context: Context): PoofWidgetCutoutPipeline =
        instance
            ?: PoofWidgetCutoutPipeline(context.applicationContext, widgetCutoutPreferences()).also { instance = it }

    suspend fun get(
        context: Context,
        source: String,
        allowProcessing: Boolean,
    ) = pipeline(context).get(source, allowProcessing)

    suspend fun clear(context: Context) = pipeline(context).clear()
}

/** Dependencies are explicit so device tests execute the same download, POST, cache and UI path. */
internal class PoofWidgetCutoutPipeline(
    private val context: Context,
    private val preferences: WidgetCutoutPreferences,
    private val client: PoofCutoutClient = PoofCutoutClient(),
    private val imageLoader: ImageLoader = SingletonImageLoader.get(context),
    private val directory: File = File(context.noBackupFilesDir, POOF_CACHE_DIRECTORY),
    private val rate: android.content.SharedPreferences =
        context.getSharedPreferences(
            "widget-poof-rate-v1",
            Context.MODE_PRIVATE,
        ),
    private val requestSpacingMillis: Long = REQUEST_SPACING_MS,
) : WidgetCutoutLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = PoofCutoutWorkQueue<WidgetCutoutArtwork>(scope)
    private val disk = Mutex()
    private val generation = AtomicLong()
    private val memory = WidgetCutoutMemoryCache()
    private val states = LinkedHashMap<String, MutableStateFlow<WidgetCutoutStatus>>()
    private val retries = mutableSetOf<String>()

    fun close() = scope.cancel()

    override fun retry(source: String) {
        logWidgetCutout("retry_requested", source)
        synchronized(retries) { retries.add(sourceKey(source)) }
    }

    override fun observe(source: String): StateFlow<WidgetCutoutStatus> =
        synchronized(states) {
            val key = sourceKey(source)
            states.getOrPut(key) { MutableStateFlow(WidgetCutoutStatus()) }.also {
                if (states.size > 256) states.remove(states.keys.first())
            }
        }

    private fun report(
        source: String,
        phase: WidgetCutoutPhase,
        message: String,
    ) {
        (observe(source) as MutableStateFlow<WidgetCutoutStatus>).value = WidgetCutoutStatus(phase, message)
        preferences.report(message)
        logWidgetCutout("state_changed", source, mapOf("phase" to phase.name, "detail" to message))
    }

    private fun sourceKey(source: String) =
        poofHash("$POOF_CACHE_VERSION\n${imageCacheKeyForUrl(source)}".toByteArray())

    override fun peek(source: String): WidgetCutoutArtwork? =
        if (preferences.configuration.value.enabled) memory.peek(source) else null

    override suspend fun get(
        source: String,
        allowProcessing: Boolean,
    ): WidgetCutoutArtwork? {
        val expectedMemoryGeneration = memory.generation()
        return load(source, allowProcessing).also {
            memory.put(source, it, expectedMemoryGeneration)
        }
    }

    private suspend fun load(
        source: String,
        allowProcessing: Boolean,
    ): WidgetCutoutArtwork? =
        withContext(Dispatchers.IO) {
            val trace = PoofRequestDiagnostics(source)
            try {
                val configuration = preferences.configuration.value
                trace.event(
                    "lookup_started",
                    mapOf(
                        "allowProcessing" to allowProcessing.toString(),
                        "enabled" to configuration.enabled.toString(),
                        "configured" to configuration.configured.toString(),
                    ),
                )
                if (!configuration.enabled) {
                    trace.event("lookup_skipped", mapOf("reason" to "disabled"))
                    return@withContext null
                }
                val cache = cache()
                val sourceKey = sourceKey(source)
                disk.withLock { cache.findSource(sourceKey) }?.let {
                    trace.event(
                        "cache_hit",
                        mapOf(
                            "kind" to "source",
                            "width" to it.width.toString(),
                            "height" to it.height.toString(),
                        ),
                    )
                    report(source, WidgetCutoutPhase.Ready, "已复用抠图缓存")
                    return@withContext it.artwork()
                }
                trace.event("cache_miss", mapOf("kind" to "source"))
                if (!allowProcessing) return@withContext null
                val key = preferences.apiKey()
                if (key.isBlank()) {
                    report(source, WidgetCutoutPhase.NeedsKey, "先配置 Poof Key")
                    return@withContext null
                }
                val credential = poofHash("$key\n${preferences.credentialGeneration}".toByteArray())
                val expectedGeneration = generation.get()
                report(source, WidgetCutoutPhase.Queued, "等待抠图处理")
                trace.event("request_enqueued")
                requests.run("$sourceKey:$credential") {
                    process(cache, source, sourceKey, key, credential, expectedGeneration, trace)
                }
            } catch (cancelled: CancellationException) {
                trace.event("lookup_cancelled", mapOf("reason" to "caller_cancelled"))
                throw cancelled
            } catch (failure: Exception) {
                trace.failure(failure)
                report(source, WidgetCutoutPhase.Failed, "缓存读取或海报加载失败，可点按重试")
                null
            } catch (failure: OutOfMemoryError) {
                trace.failure(failure)
                report(source, WidgetCutoutPhase.Failed, "内存不足，保留普通海报")
                null
            } finally {
                trace.event("lookup_finished")
            }
        }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            generation.incrementAndGet()
            val deleted =
                disk.withLock {
                    memory.clear()
                    directory.deleteRecursively()
                }
            logWidgetCutout("cache_cleared", attributes = mapOf("deleted" to deleted.toString()))
        }

    private suspend fun process(
        cache: PoofCutoutCache,
        source: String,
        sourceKey: String,
        apiKey: String,
        credential: String,
        expectedGeneration: Long,
        trace: PoofRequestDiagnostics,
    ): WidgetCutoutArtwork? {
        var contentKey: String? = null
        val explicitRetry = synchronized(retries) { retries.remove(sourceKey) }
        trace.stage = "cache"
        trace.event("work_started", mapOf("manualRetry" to explicitRetry.toString()))
        try {
            disk.withLock { cache.findSource(sourceKey) }?.let {
                trace.event("cache_hit", mapOf("kind" to "source_after_queue"))
                return it.artwork()
            }
            if (!preferences.configuration.value.enabled ||
                preferences.apiKey() != apiKey ||
                generation.get() != expectedGeneration
            ) {
                trace.event("work_skipped", mapOf("reason" to "configuration_or_cache_generation_changed"))
                return null
            }
            val now = System.currentTimeMillis()
            val blockedUntil =
                if (rate.getString("credential", "") ==
                    credential
                ) {
                    rate.getLong("blocked-until", 0L)
                } else {
                    0L
                }
            if (blockedUntil - now in 1..DAY_MS) {
                val status = rate.getInt("blocked-status", 429)
                trace.event(
                    "cooldown_active",
                    mapOf(
                        "httpStatus" to status.toString(),
                        "remainingMs" to (blockedUntil - now).toString(),
                    ),
                )
                report(
                    source,
                    if (status ==
                        402
                    ) {
                        WidgetCutoutPhase.NoCredits
                    } else if (status in
                        setOf(401, 403)
                    ) {
                        WidgetCutoutPhase.InvalidKey
                    } else {
                        WidgetCutoutPhase.Limited
                    },
                    when (status) {
                        402 -> "Poof 额度不足"
                        401, 403 -> "Poof Key 不可用，请重新配置"
                        else -> "Poof 暂时限流，稍后再试"
                    },
                )
                return null
            }
            if (!explicitRetry && disk.withLock { cache.isRejected(sourceKey, credential) }) {
                trace.event("negative_cache_hit", mapOf("kind" to "source"))
                report(source, WidgetCutoutPhase.Failed, "上次未成功，可点按重试")
                return null
            }
            report(source, WidgetCutoutPhase.Downloading, "正在读取海报")
            trace.stage = "download"
            trace.event("download_started")
            val result =
                imageLoader.execute(
                    ImageRequest
                        .Builder(context)
                        .data(source)
                        .size(768, 768)
                        .scale(Scale.FIT)
                        .precision(Precision.EXACT)
                        .allowHardware(false)
                        .build(),
                )
            // Coil owns this bitmap. The uploaded PNG contains pixels only, never its source URL.
            val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
            if (bitmap == null) {
                trace.event(
                    "download_failed",
                    (result as? ErrorResult)?.throwable?.let(::widgetCutoutFailureAttributes).orEmpty(),
                    warning = true,
                )
                report(source, WidgetCutoutPhase.Failed, "海报下载失败，抠图尚未发起")
                return null
            }
            trace.event(
                "download_ready",
                mapOf(
                    "width" to bitmap.width.toString(),
                    "height" to bitmap.height.toString(),
                    "bitmapConfig" to bitmap.config.toString(),
                    "imageDataSource" to (result as SuccessResult).dataSource.name,
                ),
            )
            trace.stage = "normalize"
            val input = preparePoofSource(bitmap)
            val original = input.bytes
            trace.event(
                "source_normalized",
                mapOf(
                    "width" to input.width.toString(),
                    "height" to input.height.toString(),
                    "bytes" to original.size.toString(),
                ),
            )
            val content = poofHash(POOF_CACHE_VERSION.toByteArray() + original)
            contentKey = content
            disk
                .withLock {
                    cache.findContent(content)?.also { cache.bindSource(sourceKey, content) }
                }?.let {
                    trace.event("cache_hit", mapOf("kind" to "content"))
                    return it.artwork()
                }
            if (!explicitRetry && disk.withLock { cache.isRejected(content, credential) }) {
                trace.event("negative_cache_hit", mapOf("kind" to "content"))
                report(source, WidgetCutoutPhase.Failed, "上次未成功，可点按重试")
                return null
            }
            val previous = rate.getLong("last-attempt", 0L)
            val elapsed = System.currentTimeMillis() - previous
            if (elapsed in 0 until requestSpacingMillis) {
                trace.stage = "rate_wait"
                trace.event("rate_wait", mapOf("waitMs" to (requestSpacingMillis - elapsed).toString()))
                report(source, WidgetCutoutPhase.Queued, "等待请求间隔，约 ${(requestSpacingMillis - elapsed + 999) / 1000} 秒")
                delay(requestSpacingMillis - elapsed)
            }
            if (!preferences.configuration.value.enabled ||
                preferences.apiKey() != apiKey ||
                generation.get() != expectedGeneration
            ) {
                trace.event("work_skipped", mapOf("reason" to "configuration_or_cache_generation_changed"))
                return null
            }
            // A crash after submission has an unknown billing outcome. Persist a short retry hold
            // before the POST, and clear it only when the resulting pair is committed.
            disk.withLock {
                cache.reject(sourceKey, POOF_TRANSIENT_TTL, credential)
                cache.reject(content, POOF_TRANSIENT_TTL, credential)
            }
            check(
                rate
                    .edit()
                    .putString(
                        "credential",
                        credential,
                    ).putLong("blocked-until", 0L)
                    .putLong("last-attempt", System.currentTimeMillis())
                    .commit(),
            )
            report(source, WidgetCutoutPhase.Processing, "Poof 正在抠图")
            trace.stage = "poof_request"
            trace.event("http_started", mapOf("uploadBytes" to original.size.toString()))
            val output =
                client.remove(apiKey, original) { response ->
                    trace.event(
                        "http_response",
                        mapOf(
                            "httpStatus" to response.status.toString(),
                            "pngContentType" to response.pngContentType.toString(),
                            "declaredBytes" to response.declaredBytes.toString(),
                        ),
                    )
                }
            trace.stage = "decode_response"
            trace.event("response_received", mapOf("bytes" to output.size.toString()))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(output, 0, output.size, bounds)
            trace.event(
                "response_dimensions",
                mapOf(
                    "width" to bounds.outWidth.toString(),
                    "height" to bounds.outHeight.toString(),
                    "expectedWidth" to input.width.toString(),
                    "expectedHeight" to input.height.toString(),
                ),
            )
            check(bounds.outWidth == input.width && bounds.outHeight == input.height)
            val foreground = checkNotNull(BitmapFactory.decodeByteArray(output, 0, output.size))
            val quality =
                try {
                    trace.stage = "quality"
                    assessPoofForeground(foreground, trace)
                } finally {
                    foreground.recycle()
                }
            if (generation.get() != expectedGeneration) {
                trace.event("work_skipped", mapOf("reason" to "cache_cleared_after_response"))
                return null
            }
            if (quality == null) {
                disk.withLock {
                    cache.reject(sourceKey, POOF_QUALITY_TTL)
                    cache.reject(content, POOF_QUALITY_TTL)
                }
                report(source, WidgetCutoutPhase.Rejected, "返回图没有有效透明前景，可点按重试")
                return null
            }
            trace.stage = "cache_write"
            val cached =
                disk.withLock {
                    if (generation.get() != expectedGeneration) return@withLock null
                    cache.write(sourceKey, content, original, output, input.width, input.height, quality)
                }
            trace.event("cache_write_finished", mapOf("saved" to (cached != null).toString()))
            report(
                source,
                if (cached == null) WidgetCutoutPhase.Failed else WidgetCutoutPhase.Ready,
                if (cached == null) "抠图已完成，但缓存保存失败" else "抠图完成，已保存缓存",
            )
            return cached?.artwork()
        } catch (cancelled: CancellationException) {
            trace.event("work_cancelled")
            throw cancelled
        } catch (failure: PoofApiException) {
            trace.failure(failure)
            trace.event(
                "http_failed",
                mapOf(
                    "httpStatus" to failure.status.toString(),
                    "retryAfterMs" to (failure.retryAfterMillis ?: -1).toString(),
                ),
                warning = true,
            )
            val hold =
                when (failure.status) {
                    401, 402, 403 -> DAY_MS
                    429 -> (failure.retryAfterMillis ?: 60_000L).coerceIn(60_000, DAY_MS)
                    else -> POOF_TRANSIENT_TTL
                }
            if (failure.status in setOf(401, 402, 403, 429)) {
                rate
                    .edit()
                    .putString("credential", credential)
                    .putInt("blocked-status", failure.status)
                    .putLong(
                        "blocked-until",
                        System.currentTimeMillis() + hold,
                    ).commit()
            }
            report(
                source,
                when (failure.status) {
                    401, 403 -> WidgetCutoutPhase.InvalidKey
                    402 -> WidgetCutoutPhase.NoCredits
                    429 -> WidgetCutoutPhase.Limited
                    else -> WidgetCutoutPhase.Failed
                },
                when (failure.status) {
                    401, 403 -> "Poof 密钥不可用，请在海报抠图设置中修改"
                    402 -> "Poof 额度不足，暂时保留普通海报；已有缓存继续可用"
                    429 -> "Poof 请求频率受限，已暂停新请求；已有缓存继续可用"
                    else -> "抠图服务暂时不可用，保留普通海报"
                },
            )
            disk.withLock {
                cache.reject(sourceKey, hold, credential)
                contentKey?.let { cache.reject(it, hold, credential) }
            }
            return null
        } catch (failure: Exception) {
            trace.failure(failure)
            report(source, WidgetCutoutPhase.Failed, "抠图连接或图片处理失败，稍后可重试")
            runCatching { disk.withLock { cache.reject(sourceKey, POOF_TRANSIENT_TTL, credential) } }
            return null
        } catch (failure: OutOfMemoryError) {
            trace.failure(failure)
            report(source, WidgetCutoutPhase.Failed, "内存不足，保留普通海报")
            return null
        } finally {
            trace.event("work_finished")
        }
    }

    private fun cache() =
        PoofCutoutCache(directory) { image, width, height ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            bounds.outWidth == width && bounds.outHeight == height
        }
}

internal data class PoofSourceImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/** A shared Coil cache may return a larger software bitmap than this request's target size. */
internal fun preparePoofSource(bitmap: Bitmap): PoofSourceImage {
    require(bitmap.width > 0 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= 40_000_000L)
    val readable =
        if (bitmap.config ==
            Bitmap.Config.HARDWARE
        ) {
            checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        } else {
            bitmap
        }
    val scale = minOf(768f / readable.width, 768f / readable.height, 1f)
    val normalized =
        if (scale <
            1f
        ) {
            Bitmap.createScaledBitmap(
                readable,
                (readable.width * scale).toInt().coerceAtLeast(1),
                (
                    readable.height *
                        scale
                ).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            readable
        }
    try {
        require(normalized.width >= 96 && normalized.height >= 96)
        val bytes =
            ByteArrayOutputStream().use {
                check(normalized.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        return PoofSourceImage(bytes, normalized.width, normalized.height)
    } finally {
        if (normalized !== readable) normalized.recycle()
        if (readable !== bitmap) readable.recycle()
    }
}

private fun PoofCachedArtwork.artwork() =
    WidgetCutoutArtwork(
        Uri.fromFile(background).toString(),
        Uri.fromFile(foreground).toString(),
        width,
        height,
        top,
        bottom,
    )

/** Cloud masks may intentionally touch the poster edge; reject empty, near-solid or diffuse masks. */
private fun assessPoofForeground(
    bitmap: Bitmap,
    trace: PoofRequestDiagnostics,
): WidgetCutoutQuality? {
    val scale = minOf(256f / bitmap.width, 256f / bitmap.height, 1f)
    val sampled =
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (
                bitmap.height *
                    scale
            ).toInt().coerceAtLeast(1),
            true,
        )
    try {
        var count = 0
        var uncertain = 0
        var left = sampled.width
        var top = sampled.height
        var right = 0
        var bottom = 0
        for (y in 0 until sampled.height) {
            for (x in 0 until sampled.width) {
                val alpha = sampled[x, y] ushr 24
                if (alpha in 26..229) uncertain++
                if (alpha < 128) continue
                count++
                left = minOf(left, x)
                right = maxOf(right, x + 1)
                top = minOf(top, y)
                bottom = maxOf(bottom, y + 1)
            }
        }
        val area = sampled.width * sampled.height
        val fraction = count.toFloat() / area
        // Cloud segmentation already made the semantic choice. A large face or several cartoon
        // characters can legitimately fill most of a poster; do not apply the local-model gate.
        val reason =
            when {
                fraction !in .015f..0.97f -> "foreground_area"
                uncertain > area * .45f || uncertain > count * .90f -> "diffuse_alpha"
                bottom - top < sampled.height * .18f -> "subject_too_short"
                else -> "accepted"
            }
        trace.event(
            "foreground_assessed",
            mapOf(
                "result" to reason,
                "foregroundFraction" to fraction.toString(),
                "uncertainPixels" to uncertain.toString(),
                "samplePixels" to area.toString(),
                "subjectTop" to (top.toFloat() / sampled.height).toString(),
                "subjectBottom" to (bottom.toFloat() / sampled.height).toString(),
            ),
        )
        if (reason != "accepted") return null
        return WidgetCutoutQuality(
            top.toFloat() / sampled.height,
            bottom.toFloat() / sampled.height,
            left.toFloat() / sampled.width,
            right.toFloat() / sampled.width,
            fraction,
        )
    } finally {
        if (sampled !== bitmap) sampled.recycle()
    }
}
