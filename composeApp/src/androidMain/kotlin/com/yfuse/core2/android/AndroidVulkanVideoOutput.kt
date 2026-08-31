package com.yfuse.core2.android

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.RequiresApi
import com.yfuse.core2.render.YGpuColorPipelineConfig
import com.yfuse.core2.render.YGpuColorTransfer
import com.yfuse.core2.render.YNativeGpuFeature
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentSkipListMap
import com.yfuse.core2.demux.YColorMatrix
import com.yfuse.core2.demux.YColorRange
import com.yfuse.core2.hdr.YHdr10PlusParser

/**
 * MediaCodec output boundary for GpuEnhanced. Decoder frames stay in protected native memory:
 * MediaCodec writes ImageReader PRIVATE images, Vulkan imports their HardwareBuffer and presents
 * them to the app Surface. No plane is mapped or copied through the CPU.
 */
@SuppressLint("NewApi")
internal class AndroidVulkanVideoOutput @RequiresApi(Build.VERSION_CODES.P) constructor(
    private val width: Int,
    private val height: Int,
    target: Surface,
    colorConfig: YGpuColorPipelineConfig,
) : Closeable {
    private val activeColorConfig = AtomicReference(colorConfig)
    private val thread = HandlerThread("YCore-Vulkan-Frames").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile
    private var imageReader = createPrivateImageReader(width.coerceAtLeast(16), height.coerceAtLeast(16))
    private val decoderWidth = AtomicInteger(width.coerceAtLeast(16))
    private val decoderHeight = AtomicInteger(height.coerceAtLeast(16))
    private val rendererLock = Any()
    private val renderer = AtomicLong(AndroidYCoreGpuNativeBridge.createRenderer(target, colorConfig.outputTransfer))
    private val featureMask = AtomicLong(AndroidYCoreGpuNativeBridge.rendererFeatureMask(renderer.get()))
    private val gpuDurationNs = AtomicLong(0L)
    private val presentedFrames = AtomicInteger(0)
    private val attemptedFrames = AtomicInteger(0)
    private val frameIndex = AtomicInteger(0)
    private val pendingHdr10Plus = ConcurrentSkipListMap<Long, com.yfuse.core2.hdr.YHdr10PlusSceneMetadata>()

    val decoderSurface: Surface get() = imageReader.surface
    val isReady: Boolean get() = renderer.get() != 0L && decoderSurface.isValid
    val currentFeatureMask: Long get() = featureMask.get()
    val lastGpuFrameDurationNs: Long get() = gpuDurationNs.get()
    val renderedFrameCount: Int get() = presentedFrames.get()
    val outputVerified: Boolean
        get() =
            currentFeatureMask and YNativeGpuFeature.OutputMeasured.mask != 0L &&
                currentFeatureMask and YNativeGpuFeature.DecodedFramePresented.mask != 0L &&
                (
                    activeColorConfig.get().sourceTransfer == YGpuColorTransfer.Sdr ||
                        currentFeatureMask and YNativeGpuFeature.P010Input.mask != 0L
                )
    val measurementFailed: Boolean
        get() =
            (attemptedFrames.get() >= MAX_MEASUREMENT_FRAMES && !outputVerified) ||
                (
                    activeColorConfig.get().sourceTransfer != YGpuColorTransfer.Sdr &&
                        renderedFrameCount >= MAX_P010_PROBE_FRAMES &&
                        currentFeatureMask and YNativeGpuFeature.P010Input.mask == 0L
                )

    fun setTargetSurface(target: Surface): Boolean {
        if (!target.isValid) return false
        val replacement = AndroidYCoreGpuNativeBridge.createRenderer(target, activeColorConfig.get().outputTransfer)
        if (replacement == 0L) return false
        synchronized(rendererLock) {
            AndroidYCoreGpuNativeBridge.destroyRenderer(renderer.getAndSet(replacement))
            featureMask.set(AndroidYCoreGpuNativeBridge.rendererFeatureMask(replacement))
            gpuDurationNs.set(0L)
            presentedFrames.set(0)
            attemptedFrames.set(0)
        }
        return true
    }

    fun updateDecodedFormat(
        format: MediaFormat,
        switchDecoderSurface: (Surface) -> Unit,
    ): Boolean {
        val decodedWidth = format.integerOrNull(MediaFormat.KEY_WIDTH)?.coerceAtLeast(16) ?: decoderWidth.get()
        val decodedHeight = format.integerOrNull(MediaFormat.KEY_HEIGHT)?.coerceAtLeast(16) ?: decoderHeight.get()
        activeColorConfig.updateAndGet { current ->
            val cropLeft = format.integerOrNull("crop-left")?.coerceIn(0, decodedWidth - 1) ?: current.geometry.cropLeft
            val cropTop = format.integerOrNull("crop-top")?.coerceIn(0, decodedHeight - 1) ?: current.geometry.cropTop
            val cropRightCoordinate = format.integerOrNull("crop-right")
            val cropBottomCoordinate = format.integerOrNull("crop-bottom")
            current.copy(
                sourceRange =
                    when (format.integerOrNull(MediaFormat.KEY_COLOR_RANGE)) {
                        MediaFormat.COLOR_RANGE_FULL -> YColorRange.Full
                        MediaFormat.COLOR_RANGE_LIMITED -> YColorRange.Limited
                        else -> current.sourceRange
                    },
                sourceMatrix =
                    when (format.integerOrNull(MediaFormat.KEY_COLOR_STANDARD)) {
                        MediaFormat.COLOR_STANDARD_BT2020 -> YColorMatrix.Bt2020
                        MediaFormat.COLOR_STANDARD_BT709 -> YColorMatrix.Bt709
                        MediaFormat.COLOR_STANDARD_BT601_NTSC,
                        MediaFormat.COLOR_STANDARD_BT601_PAL,
                        -> YColorMatrix.Bt601
                        else -> current.sourceMatrix
                    },
                geometry =
                    current.geometry.copy(
                        pixelAspectRatioNumerator = format.integerOrNull("sar-width")?.coerceAtLeast(1)
                            ?: current.geometry.pixelAspectRatioNumerator,
                        pixelAspectRatioDenominator = format.integerOrNull("sar-height")?.coerceAtLeast(1)
                            ?: current.geometry.pixelAspectRatioDenominator,
                        rotationDegrees = format.integerOrNull(MediaFormat.KEY_ROTATION)
                            ?: current.geometry.rotationDegrees,
                        cropLeft = cropLeft,
                        cropTop = cropTop,
                        cropRight = cropRightCoordinate?.let { (decodedWidth - 1 - it).coerceAtLeast(0) }
                            ?: current.geometry.cropRight,
                        cropBottom = cropBottomCoordinate?.let { (decodedHeight - 1 - it).coerceAtLeast(0) }
                            ?: current.geometry.cropBottom,
                ),
            )
        }
        if (decodedWidth == decoderWidth.get() && decodedHeight == decoderHeight.get()) return false

        val replacement = createPrivateImageReader(decodedWidth, decodedHeight)
        attachImageListener(replacement)
        try {
            switchDecoderSurface(replacement.surface)
        } catch (failure: Throwable) {
            replacement.setOnImageAvailableListener(null, null)
            replacement.close()
            throw failure
        }
        val previous = imageReader
        imageReader = replacement
        decoderWidth.set(decodedWidth)
        decoderHeight.set(decodedHeight)
        handler.post {
            previous.setOnImageAvailableListener(null, null)
            runCatching(previous::close)
        }
        return true
    }

    fun queueHdr10PlusMetadata(
        presentationTimeUs: Long,
        ituT35Payload: ByteArray,
    ) {
        val parsed = YHdr10PlusParser.parse(ituT35Payload) ?: return
        pendingHdr10Plus[presentationTimeUs.coerceAtLeast(0L)] = parsed
        while (pendingHdr10Plus.size > MAX_PENDING_DYNAMIC_METADATA) pendingHdr10Plus.pollFirstEntry()
    }

    init {
        attachImageListener(imageReader)
    }

    private fun attachImageListener(reader: ImageReader) {
        reader.setOnImageAvailableListener(
            { reader ->
                val image = runCatching(reader::acquireLatestImage).getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    attemptedFrames.incrementAndGet()
                    applyHdr10PlusForTimestamp(image.timestamp / 1_000L)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val producerFinished =
                            runCatching {
                                image.fence.use { fence ->
                                    !fence.isValid || fence.await(PRODUCER_FENCE_TIMEOUT)
                                }
                            }.getOrDefault(false)
                        if (!producerFinished) return@use
                    }
                    val hardwareBuffer = runCatching { image.hardwareBuffer }.getOrNull() ?: return@use
                    hardwareBuffer.use {
                        synchronized(rendererLock) {
                            val handle = renderer.get()
                            if (handle == 0L) return@synchronized
                            val mask =
                                AndroidYCoreGpuNativeBridge.renderHardwareBuffer(
                                    renderer = handle,
                                    buffer = hardwareBuffer,
                                    config = activeColorConfig.get(),
                                    frameIndex = frameIndex.getAndIncrement(),
                                )
                            featureMask.set(mask)
                            gpuDurationNs.set(AndroidYCoreGpuNativeBridge.lastGpuDurationNs(handle))
                            if (mask and YNativeGpuFeature.DecodedFramePresented.mask != 0L) {
                                presentedFrames.incrementAndGet()
                            }
                        }
                    }
                }
            },
            handler,
        )
    }

    override fun close() {
        imageReader.setOnImageAvailableListener(null, null)
        runCatching(imageReader::close)
        synchronized(rendererLock) {
            AndroidYCoreGpuNativeBridge.destroyRenderer(renderer.getAndSet(0L))
        }
        thread.quitSafely()
    }

    private fun applyHdr10PlusForTimestamp(presentationTimeUs: Long) {
        val entry =
            pendingHdr10Plus.floorEntry(presentationTimeUs)
                ?: pendingHdr10Plus.ceilingEntry(presentationTimeUs)
                ?: return
        if (kotlin.math.abs(entry.key - presentationTimeUs) > MAX_DYNAMIC_METADATA_DISTANCE_US) return
        activeColorConfig.updateAndGet { it.copy(hdr10PlusSceneMetadata = entry.value) }
        pendingHdr10Plus.headMap(entry.key, true).clear()
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun createPrivateImageReader(
    width: Int,
    height: Int,
): ImageReader =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ImageReader.newInstance(
            width,
            height,
            ImageFormat.PRIVATE,
            MAX_GPU_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
    } else {
        ImageReader.newInstance(width, height, ImageFormat.PRIVATE, MAX_GPU_IMAGES)
    }

private const val MAX_GPU_IMAGES = 4
private const val MAX_MEASUREMENT_FRAMES = 48
private const val MAX_P010_PROBE_FRAMES = 12
private const val MAX_PENDING_DYNAMIC_METADATA = 96
private const val MAX_DYNAMIC_METADATA_DISTANCE_US = 1_000_000L
private val PRODUCER_FENCE_TIMEOUT: Duration = Duration.ofMillis(250)

private fun MediaFormat.integerOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
