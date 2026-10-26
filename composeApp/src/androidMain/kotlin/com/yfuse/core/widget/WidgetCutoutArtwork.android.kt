package com.yfuse.core.widget

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import com.yfuse.core.designsystem.LocalRouteVisible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.roundToInt

private const val CUTOUT_MODEL_SIZE = 320
private const val CUTOUT_SOURCE_SIZE = 768
private const val CUTOUT_CACHE_VERSION = "u2netp-1-gate1"
private const val CUTOUT_MODEL_SHA = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"
private const val CUTOUT_SUCCESS_TTL = 7L * 24 * 60 * 60 * 1000
private const val CUTOUT_QUALITY_FAILURE_TTL = 24L * 60 * 60 * 1000
private const val CUTOUT_TRANSIENT_FAILURE_TTL = 10L * 60 * 1000
private const val CUTOUT_CACHE_BUDGET = 32L * 1024 * 1024
private const val CUTOUT_CACHE_ENTRIES = 64

private val trustedPreviewPosters =
    setOf(
        "file:///android_asset/widget-preview/space.png",
        "file:///android_asset/widget-preview/rain.png",
        "file:///android_asset/widget-preview/breakout-astronaut.png",
    )

@Composable
actual fun rememberWidgetCutoutState(
    posterUrl: String?,
    enabled: Boolean,
): WidgetCutoutViewState {
    val context = LocalContext.current.applicationContext
    val visible = LocalRouteVisible.current
    val runtime =
        LocalWidgetCutoutRuntime.current ?: remember(context) {
            WidgetCutoutRuntime(
                widgetCutoutPreferences().configuration,
                object : WidgetCutoutLoader {
                    override fun observe(source: String) = PoofWidgetCutoutStore.pipeline(context).observe(source)

                    override suspend fun get(
                        source: String,
                        allowProcessing: Boolean,
                    ) = WidgetCutoutStore.get(context, source, allowProcessing)

                    override fun retry(source: String) = PoofWidgetCutoutStore.pipeline(context).retry(source)
                },
            )
        }
    val configuration by runtime.configuration.collectAsState()
    val source = remember(posterUrl) { safeCutoutSource(posterUrl) }
    val observed = remember(source, runtime) { runtime.loader.observe(source.orEmpty()) }
    val status by observed.collectAsState()
    var attempt by remember(source) { mutableIntStateOf(0) }
    var artwork by remember(source) { mutableStateOf(precomputedPreviewCutout(source)) }
    LaunchedEffect(source, enabled, visible, configuration, attempt) {
        if (source == null || !visible) return@LaunchedEffect
        artwork = precomputedPreviewCutout(source) ?: runtime.loader.get(source, allowProcessing = false)
        if (artwork == null && enabled && configuration.enabled && configuration.configured) {
            delay(450)
            artwork = runtime.loader.get(source, allowProcessing = true)
        }
    }
    return WidgetCutoutViewState(
        artwork.takeIf { visible },
        when {
            artwork != null -> WidgetCutoutStatus(WidgetCutoutPhase.Ready, "抠图完成")
            !configuration.enabled -> WidgetCutoutStatus(WidgetCutoutPhase.Disabled, "海报抠图已关闭")
            !configuration.configured -> WidgetCutoutStatus(WidgetCutoutPhase.NeedsKey, "先配置 Poof Key")
            source == null -> WidgetCutoutStatus(WidgetCutoutPhase.Failed, "没有可处理的海报")
            else -> status
        },
        retry =
            source?.let {
                {
                    runtime.loader.retry(it)
                    attempt++
                }
            },
    )
}

/** An original demo pair, actually segmented with this model and visually reviewed before bundling. */
private fun precomputedPreviewCutout(source: String?): WidgetCutoutArtwork? =
    if (source == "file:///android_asset/widget-preview/breakout-astronaut.png") {
        WidgetCutoutArtwork(
            backgroundModel = source,
            foregroundModel = "file:///android_asset/widget-cutout/preview-astronaut.png",
            sourceWidth = 512,
            sourceHeight = 768,
            subjectTop = .153125f,
            subjectBottom = .815625f,
        )
    } else {
        null
    }

/** Never allow an arbitrary file/content URI to enter the image loader through widget data. */
private fun safeCutoutSource(value: String?): String? {
    val source = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8192 } ?: return null
    if (source in trustedPreviewPosters) return source
    return try {
        val uri = URI(source)
        source.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() }
    } catch (_: Exception) {
        null
    }
}

/** One bounded pipeline for both phone and TV. It shares the application's Coil/network policy. */
internal object WidgetCutoutStore {
    suspend fun get(
        context: Context,
        source: String,
        allowProcessing: Boolean = true,
    ): WidgetCutoutArtwork? {
        val safeSource = safeCutoutSource(source) ?: return null
        return try {
            if (safeSource in trustedPreviewPosters) {
                LocalWidgetCutoutStore.get(context, safeSource, allowProcessing)
            } else {
                PoofWidgetCutoutStore.get(context, safeSource, allowProcessing)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}

/** The bundled model is retained for the offline preview and its existing runtime checks only. */
private object LocalWidgetCutoutStore {
    private val gate = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: OrtSession? = null
    private var idleClose: Job? = null
    private var runtimeRetryAt = 0L

    suspend fun get(
        context: Context,
        source: String,
        allowProcessing: Boolean = true,
    ): WidgetCutoutArtwork? =
        withContext(Dispatchers.IO) {
            val safeSource = safeCutoutSource(source) ?: return@withContext null
            gate.withPermit {
                idleClose?.cancel()
                idleClose = null
                val directory = File(context.cacheDir, "widget-cutout")
                val key = sha256("$CUTOUT_CACHE_VERSION\n$safeSource".toByteArray(Charsets.UTF_8))
                try {
                    if (!directory.isDirectory && !directory.mkdirs()) return@withPermit null
                    trimCache(directory)
                    readArtwork(directory, key)?.let { return@withPermit it }
                    if (!allowProcessing) return@withPermit null
                    if (isRejected(directory, key)) return@withPermit null
                    val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    if (memory?.isLowRamDevice == true || (memory != null && memory.memoryClass < 128)) {
                        reject(directory, key, CUTOUT_QUALITY_FAILURE_TTL)
                        return@withPermit null
                    }
                    if (System.currentTimeMillis() < runtimeRetryAt) return@withPermit null
                    val result =
                        SingletonImageLoader.get(context).execute(
                            ImageRequest
                                .Builder(context)
                                .data(safeSource)
                                .size(CUTOUT_SOURCE_SIZE, CUTOUT_SOURCE_SIZE)
                                .scale(Scale.FIT)
                                .precision(Precision.EXACT)
                                .allowHardware(false)
                                .build(),
                        )
                    currentCoroutineContext().ensureActive()
                    // The bitmap is Coil-owned. Never recycle it or modify its pixels.
                    val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
                    if (bitmap == null ||
                        bitmap.width !in 96..CUTOUT_SOURCE_SIZE ||
                        bitmap.height !in 96..CUTOUT_SOURCE_SIZE
                    ) {
                        reject(directory, key, CUTOUT_TRANSIENT_FAILURE_TTL)
                        return@withPermit null
                    }
                    val input = prepareWidgetCutoutInput(bitmap)
                    currentCoroutineContext().ensureActive()
                    val mask = runModel(getSession(context), input)
                    currentCoroutineContext().ensureActive()
                    val quality = assessWidgetCutoutMask(mask, CUTOUT_MODEL_SIZE, CUTOUT_MODEL_SIZE)
                    if (quality == null) {
                        reject(directory, key, CUTOUT_QUALITY_FAILURE_TTL)
                        return@withPermit null
                    }
                    val foreground = createForeground(bitmap, mask)
                    try {
                        currentCoroutineContext().ensureActive()
                        writeArtwork(directory, key, bitmap, foreground, quality)
                    } finally {
                        foreground.recycle()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: OutOfMemoryError) {
                    closeSession()
                    runtimeRetryAt = System.currentTimeMillis() + CUTOUT_TRANSIENT_FAILURE_TTL
                    null
                } catch (_: LinkageError) {
                    closeSession()
                    runtimeRetryAt = System.currentTimeMillis() + CUTOUT_TRANSIENT_FAILURE_TTL
                    null
                } catch (_: Exception) {
                    // Native model, corrupt image and disk errors all preserve the normal poster.
                    currentCoroutineContext().ensureActive()
                    closeSession()
                    runCatching { reject(directory, key, CUTOUT_TRANSIENT_FAILURE_TTL) }
                    null
                } finally {
                    // A cancelled request cannot retain a partial pair or replace a good cache entry.
                    runCatching {
                        directory
                            .listFiles { file -> file.name.startsWith("$key.") && file.name.endsWith(".part") }
                            ?.forEach(File::delete)
                        trimCache(directory)
                    }
                    if (session != null) {
                        idleClose =
                            scope.launch {
                                delay(30_000)
                                gate.withPermit { closeSession() }
                            }
                    }
                }
            }
        }

    private fun getSession(context: Context): OrtSession {
        session?.let { return it }
        val created =
            try {
                val bytes = context.assets.open("widget-cutout/u2netp.onnx").use { it.readBytes() }
                check(sha256(bytes) == CUTOUT_MODEL_SHA) { "Invalid foreground model" }
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    options.setInterOpNumThreads(1)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    options.addConfigEntry("session.inter_op.allow_spinning", "0")
                    OrtEnvironment.getEnvironment().createSession(bytes, options)
                }
            } catch (failure: Exception) {
                runtimeRetryAt = System.currentTimeMillis() + CUTOUT_TRANSIENT_FAILURE_TTL
                throw failure
            }
        session = created
        return created
    }

    private fun closeSession() {
        val previous = session
        session = null
        runCatching { previous?.close() }
        // OrtEnvironment is process-wide and may also be used by other features.
    }
}

internal fun prepareWidgetCutoutInput(source: Bitmap): FloatArray {
    val scaled = Bitmap.createScaledBitmap(source, CUTOUT_MODEL_SIZE, CUTOUT_MODEL_SIZE, true)
    try {
        val pixels = IntArray(CUTOUT_MODEL_SIZE * CUTOUT_MODEL_SIZE)
        scaled.getPixels(pixels, 0, CUTOUT_MODEL_SIZE, 0, 0, CUTOUT_MODEL_SIZE, CUTOUT_MODEL_SIZE)
        var maximum = 1
        for (pixel in pixels) {
            maximum = maxOf(maximum, (pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255)
        }
        val divisor = maximum.toFloat()
        val result = FloatArray(pixels.size * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            result[index] = (((pixel ushr 16) and 255) / divisor - .485f) / .229f
            result[index + pixels.size] = (((pixel ushr 8) and 255) / divisor - .456f) / .224f
            result[index + pixels.size * 2] = ((pixel and 255) / divisor - .406f) / .225f
        }
        return result
    } finally {
        if (scaled !== source) scaled.recycle()
    }
}

/** Cancellation terminates the native run; all native handles still close on the worker thread. */
private suspend fun runModel(
    session: OrtSession,
    input: FloatArray,
): FloatArray =
    suspendCancellableCoroutine { continuation ->
        val options = OrtSession.RunOptions()
        val optionLock = Any()
        var optionsOpen = true
        continuation.invokeOnCancellation {
            synchronized(optionLock) {
                if (optionsOpen) runCatching { options.setTerminate(true) }
            }
        }
        try {
            val mask =
                OnnxTensor
                    .createTensor(
                        OrtEnvironment.getEnvironment(),
                        FloatBuffer.wrap(input),
                        longArrayOf(1, 3, CUTOUT_MODEL_SIZE.toLong(), CUTOUT_MODEL_SIZE.toLong()),
                    ).use { tensor ->
                        session
                            .run(
                                mapOf(session.inputNames.first() to tensor),
                                setOf(session.outputNames.first()),
                                options,
                            ).use { result ->
                                val output = (result[0] as? OnnxTensor)?.floatBuffer
                                check(output != null && output.remaining() == CUTOUT_MODEL_SIZE * CUTOUT_MODEL_SIZE)
                                val mask = FloatArray(output.remaining())
                                output.get(mask)
                                var minimum = Float.POSITIVE_INFINITY
                                var maximum = Float.NEGATIVE_INFINITY
                                for (value in mask) {
                                    check(value.isFinite())
                                    minimum = minOf(minimum, value)
                                    maximum = maxOf(maximum, value)
                                }
                                val range = maximum - minimum
                                if (range <= 0.00001f) {
                                    mask.fill(0f)
                                } else {
                                    for (index in mask.indices) {
                                        mask[index] =
                                            ((mask[index] - minimum) / range).coerceIn(0f, 1f)
                                    }
                                }
                                mask
                            }
                    }
            continuation.resume(mask)
        } catch (failure: Throwable) {
            continuation.resumeWithException(failure)
        } finally {
            synchronized(optionLock) {
                optionsOpen = false
                runCatching { options.close() }
            }
        }
    }

/** Bilinear resampling changes alpha only; the RGB pixels and source coordinates stay aligned. */
private suspend fun createForeground(
    source: Bitmap,
    mask: FloatArray,
): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (y in 0 until height) {
        if (y % 32 == 0) currentCoroutineContext().ensureActive()
        val sy = ((y + .5f) * CUTOUT_MODEL_SIZE / height - .5f).coerceIn(0f, (CUTOUT_MODEL_SIZE - 1).toFloat())
        val y0 = floor(sy).toInt()
        val y1 = minOf(y0 + 1, CUTOUT_MODEL_SIZE - 1)
        val fy = sy - y0
        for (x in 0 until width) {
            val sx = ((x + .5f) * CUTOUT_MODEL_SIZE / width - .5f).coerceIn(0f, (CUTOUT_MODEL_SIZE - 1).toFloat())
            val x0 = floor(sx).toInt()
            val x1 = minOf(x0 + 1, CUTOUT_MODEL_SIZE - 1)
            val fx = sx - x0
            val upper = mask[y0 * CUTOUT_MODEL_SIZE + x0] * (1f - fx) + mask[y0 * CUTOUT_MODEL_SIZE + x1] * fx
            val lower = mask[y1 * CUTOUT_MODEL_SIZE + x0] * (1f - fx) + mask[y1 * CUTOUT_MODEL_SIZE + x1] * fx
            val matte = upper * (1f - fy) + lower * fy
            val pixel = pixels[y * width + x]
            val alpha = ((pixel ushr 24) * matte).roundToInt().coerceIn(0, 255)
            pixels[y * width + x] = (pixel and 0x00ffffff) or (alpha shl 24)
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun artworkFiles(
    directory: File,
    key: String,
): Pair<File, File> = File(directory, "$key.background.png") to File(directory, "$key.foreground.png")

private fun readArtwork(
    directory: File,
    key: String,
): WidgetCutoutArtwork? {
    val metadata = File(directory, "$key.meta")
    if (!metadata.isFile || metadata.length() > 2048) return null
    val values = runCatching { metadata.readLines() }.getOrNull() ?: return null
    if (values.size != 7 || values[0] != CUTOUT_CACHE_VERSION) return null
    val width = values[1].toIntOrNull() ?: return null
    val height = values[2].toIntOrNull() ?: return null
    val top = values[3].toFloatOrNull() ?: return null
    val bottom = values[4].toFloatOrNull() ?: return null
    val created = values[5].toLongOrNull() ?: return null
    val now = System.currentTimeMillis()
    if (width !in 96..CUTOUT_SOURCE_SIZE ||
        height !in 96..CUTOUT_SOURCE_SIZE ||
        !top.isFinite() ||
        !bottom.isFinite() ||
        top !in 0f..1f ||
        bottom !in 0f..1f ||
        top >= bottom ||
        created > now ||
        now - created > CUTOUT_SUCCESS_TTL ||
        values[6] != "complete"
    ) {
        return null
    }
    val (background, foreground) = artworkFiles(directory, key)
    if (!background.isFile || !foreground.isFile || background.length() <= 0 || foreground.length() <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    for (image in listOf(background, foreground)) {
        BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth != width || bounds.outHeight != height) return null
    }
    metadata.setLastModified(now)
    return WidgetCutoutArtwork(
        Uri.fromFile(background).toString(),
        Uri.fromFile(foreground).toString(),
        width,
        height,
        top,
        bottom,
    )
}

private fun isRejected(
    directory: File,
    key: String,
): Boolean {
    val marker = File(directory, "$key.reject")
    if (!marker.isFile || marker.length() > 64) return false
    val expires = runCatching { marker.readText().toLongOrNull() }.getOrNull() ?: return false
    val now = System.currentTimeMillis()
    return (expires > now && expires - now <= CUTOUT_QUALITY_FAILURE_TTL).also { rejected ->
        if (rejected) marker.setLastModified(now) else marker.delete()
    }
}

private fun reject(
    directory: File,
    key: String,
    ttl: Long,
) {
    removeEntry(directory, key)
    File(directory, "$key.reject").writeText((System.currentTimeMillis() + ttl).toString())
    trimCache(directory, key)
}

private suspend fun writeArtwork(
    directory: File,
    key: String,
    background: Bitmap,
    foreground: Bitmap,
    quality: WidgetCutoutQuality,
): WidgetCutoutArtwork? {
    val (backgroundFile, foregroundFile) = artworkFiles(directory, key)
    for ((bitmap, target) in listOf(background to backgroundFile, foreground to foregroundFile)) {
        currentCoroutineContext().ensureActive()
        val temporary = File(directory, "${target.name}.part")
        temporary.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        currentCoroutineContext().ensureActive()
        check(temporary.renameTo(target))
    }
    currentCoroutineContext().ensureActive()
    val metadata = File(directory, "$key.meta")
    val temporary = File(directory, "$key.meta.part")
    temporary.writeText(
        listOf(
            CUTOUT_CACHE_VERSION,
            background.width,
            background.height,
            quality.top,
            quality.bottom,
            System.currentTimeMillis(),
            "complete",
        ).joinToString("\n"),
    )
    check(temporary.renameTo(metadata))
    File(directory, "$key.reject").delete()
    trimCache(directory, key)
    return readArtwork(directory, key)
}

private fun removeEntry(
    directory: File,
    key: String,
) {
    directory.listFiles { file -> file.name.startsWith("$key.") }?.forEach(File::delete)
}

/** Least-recently-used pairs and failure records share one hard budget; orphaned writes expire. */
private fun trimCache(
    directory: File,
    preserve: String? = null,
) {
    val now = System.currentTimeMillis()
    val groups = directory.listFiles()?.filter(File::isFile)?.groupBy { it.name.substringBefore('.') } ?: return
    var bytes = groups.values.sumOf { files -> files.sumOf(File::length) }
    var entries = groups.size
    val sorted =
        groups.entries.sortedBy { (_, files) ->
            files.firstOrNull { it.extension == "meta" || it.extension == "reject" }?.lastModified() ?: 0L
        }
    for ((key, files) in sorted) {
        if (key == preserve) continue
        val marker = files.firstOrNull { it.extension == "meta" || it.extension == "reject" }
        val orphaned = marker == null
        val expired = marker != null && now - marker.lastModified() > CUTOUT_SUCCESS_TTL
        if (orphaned || expired || bytes > CUTOUT_CACHE_BUDGET || entries > CUTOUT_CACHE_ENTRIES) {
            for (file in files) {
                val length = file.length()
                if (file.delete()) bytes -= length
            }
            entries--
        }
    }
}
