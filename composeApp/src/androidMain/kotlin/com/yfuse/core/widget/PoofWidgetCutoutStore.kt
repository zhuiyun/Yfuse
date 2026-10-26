package com.yfuse.core.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.get
import coil3.BitmapImage
import coil3.SingletonImageLoader
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
import kotlinx.coroutines.delay
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = PoofCutoutWorkQueue<WidgetCutoutArtwork>(scope)
    private val disk = Mutex()
    private val generation = AtomicLong()
    private val client = PoofCutoutClient()

    suspend fun get(
        context: Context,
        source: String,
        allowProcessing: Boolean,
    ): WidgetCutoutArtwork? =
        withContext(Dispatchers.IO) {
            val preferences = widgetCutoutPreferences()
            if (!preferences.configuration.value.enabled) return@withContext null
            val cache = cache(context)
            val sourceKey = poofHash("$POOF_CACHE_VERSION\n${imageCacheKeyForUrl(source)}".toByteArray())
            disk.withLock { cache.findSource(sourceKey) }?.let { return@withContext it.artwork() }
            if (!allowProcessing) return@withContext null
            val key = preferences.apiKey()
            if (key.isBlank()) return@withContext null
            val credential = poofHash(key.toByteArray())
            val expectedGeneration = generation.get()
            requests.run("$sourceKey:$credential") {
                process(context, cache, source, sourceKey, key, credential, expectedGeneration)
            }
        }

    suspend fun clear(context: Context) =
        withContext(Dispatchers.IO) {
            generation.incrementAndGet()
            disk.withLock { File(context.noBackupFilesDir, POOF_CACHE_DIRECTORY).deleteRecursively() }
        }

    private suspend fun process(
        context: Context,
        cache: PoofCutoutCache,
        source: String,
        sourceKey: String,
        apiKey: String,
        credential: String,
        expectedGeneration: Long,
    ): WidgetCutoutArtwork? {
        val preferences = widgetCutoutPreferences()
        val rate = context.getSharedPreferences("widget-poof-rate-v1", Context.MODE_PRIVATE)
        var contentKey: String? = null
        try {
            disk.withLock { cache.findSource(sourceKey) }?.let { return it.artwork() }
            if (!preferences.configuration.value.enabled ||
                preferences.apiKey() != apiKey ||
                generation.get() != expectedGeneration
            ) {
                return null
            }
            if (disk.withLock { cache.isRejected(sourceKey, credential) }) return null
            val now = System.currentTimeMillis()
            val blockedUntil =
                if (rate.getString("credential", "") ==
                    credential
                ) {
                    rate.getLong("blocked-until", 0L)
                } else {
                    0L
                }
            if (blockedUntil - now in 1..DAY_MS) return null
            val result =
                SingletonImageLoader.get(context).execute(
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
            val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return null
            if (bitmap.width !in 96..768 || bitmap.height !in 96..768) return null
            val original =
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            val content = poofHash(POOF_CACHE_VERSION.toByteArray() + original)
            contentKey = content
            disk
                .withLock {
                    cache.findContent(content)?.also { cache.bindSource(sourceKey, content) }
                }?.let { return it.artwork() }
            if (disk.withLock { cache.isRejected(content, credential) }) return null
            val previous = rate.getLong("last-attempt", 0L)
            val elapsed = System.currentTimeMillis() - previous
            if (elapsed in 0 until REQUEST_SPACING_MS) delay(REQUEST_SPACING_MS - elapsed)
            if (!preferences.configuration.value.enabled ||
                preferences.apiKey() != apiKey ||
                generation.get() != expectedGeneration
            ) {
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
            preferences.report("正在生成海报抠图")
            val output = client.remove(apiKey, original)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(output, 0, output.size, bounds)
            check(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height)
            val foreground = checkNotNull(BitmapFactory.decodeByteArray(output, 0, output.size))
            val quality =
                try {
                    assessPoofForeground(foreground)
                } finally {
                    foreground.recycle()
                }
            if (generation.get() != expectedGeneration) return null
            if (quality == null) {
                disk.withLock {
                    cache.reject(sourceKey, POOF_QUALITY_TTL)
                    cache.reject(content, POOF_QUALITY_TTL)
                }
                preferences.report("这张海报不适合破框，保留普通海报")
                return null
            }
            val cached =
                disk.withLock {
                    if (generation.get() != expectedGeneration) return@withLock null
                    cache.write(sourceKey, content, original, output, bitmap.width, bitmap.height, quality)
                }
            preferences.report("生成完成，之后复用本机缓存")
            return cached?.artwork()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PoofApiException) {
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
                    .putLong(
                        "blocked-until",
                        System.currentTimeMillis() + hold,
                    ).commit()
            }
            preferences.report(
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
        } catch (_: Exception) {
            preferences.report("抠图暂未完成，保留普通海报，稍后可重试")
            runCatching { disk.withLock { cache.reject(sourceKey, POOF_TRANSIENT_TTL, credential) } }
            return null
        } catch (_: OutOfMemoryError) {
            preferences.report("内存不足，保留普通海报")
            return null
        }
    }

    private fun cache(context: Context) =
        PoofCutoutCache(File(context.noBackupFilesDir, POOF_CACHE_DIRECTORY)) { image, width, height ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            bounds.outWidth == width && bounds.outHeight == height
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
private fun assessPoofForeground(bitmap: Bitmap): WidgetCutoutQuality? {
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
        if (fraction !in .035f..0.70f || uncertain > area * .14f || uncertain > count * .45f) return null
        if (bottom - top < sampled.height * .18f) return null
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
