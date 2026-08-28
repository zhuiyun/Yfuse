package com.yfuse.core2.android

import android.content.Context
import android.os.Build
import android.hardware.HardwareBuffer
import android.view.Surface
import com.yfuse.core2.render.YGpuColorPipelineConfig
import com.yfuse.core2.render.YGpuColorTransfer
import com.yfuse.core2.render.MIN_ANDROID_HARDWARE_BUFFER_API
import com.yfuse.core2.render.NATIVE_GPU_API_VERSION
import com.yfuse.core2.render.YNativeGpuRuntimeProbe

/**
 * Fail-closed bridge for the native Vulkan/AHardwareBuffer probe.
 *
 * Loading or probing never makes Vulkan a playback route. The common gate additionally requires a
 * real decoded frame, swapchain presentation, and measured output evidence.
 */
internal object AndroidYCoreGpuRuntime {
    fun probe(
        context: Context? = null,
        evidenceKey: YCoreGpuEvidenceKey? = null,
    ): YNativeGpuRuntimeProbe {
        if (Build.VERSION.SDK_INT < MIN_ANDROID_HARDWARE_BUFFER_API) return unavailableProbe()
        val apiVersion = AndroidYCoreGpuNativeBridge.apiVersionOrZero()
        val staticFeatures =
            if (apiVersion == NATIVE_GPU_API_VERSION) {
                AndroidYCoreGpuNativeBridge.probeFeaturesOrZero()
            } else {
                0L
            }
        val measuredFeatures = context?.let { AndroidYCoreGpuEvidenceStore(it).verifiedFeatureMask(evidenceKey) } ?: 0L
        return YNativeGpuRuntimeProbe(
            platformApiLevel = Build.VERSION.SDK_INT,
            nativeApiVersion = apiVersion,
            featureMask = staticFeatures or measuredFeatures,
        )
    }

    private fun unavailableProbe() =
        YNativeGpuRuntimeProbe(
            platformApiLevel = Build.VERSION.SDK_INT,
            nativeApiVersion = 0,
            featureMask = 0L,
        )
}

internal object AndroidYCoreGpuNativeBridge {
    private val libraryLoaded: Boolean by lazy {
        YCORE_GPU_NATIVE_LIBRARIES.any { library ->
            runCatching {
                System.loadLibrary(library)
                true
            }.getOrDefault(false)
        }
    }

    fun apiVersionOrZero(): Int = if (libraryLoaded) runCatching(::nativeGpuApiVersion).getOrDefault(0) else 0

    fun probeFeaturesOrZero(): Long = if (libraryLoaded) runCatching(::nativeProbeGpuFeatures).getOrDefault(0L) else 0L

    fun createRenderer(
        surface: Surface,
        outputTransfer: YGpuColorTransfer,
    ): Long = if (libraryLoaded) runCatching { nativeCreateRenderer(surface, outputTransfer.ordinal) }.getOrDefault(0L) else 0L

    fun renderHardwareBuffer(
        renderer: Long,
        buffer: HardwareBuffer,
        config: YGpuColorPipelineConfig,
        frameIndex: Int,
    ): Long =
        if (libraryLoaded && renderer != 0L) {
            runCatching {
                nativeRenderHardwareBuffer(
                    renderer = renderer,
                    hardwareBuffer = buffer,
                    sourceTransfer = config.sourceTransfer.ordinal,
                    outputTransfer = config.outputTransfer.ordinal,
                    sourcePrimaries = config.sourcePrimaries.ordinal,
                    outputPrimaries = config.outputPrimaries.ordinal,
                    scalingFilter = config.scalingFilter.ordinal,
                    sourceBitDepth = config.sourceBitDepth,
                    frameIndex = frameIndex,
                    toneMapEnabled = config.toneMapper != null,
                    sourcePeakNits = config.sourcePeakNits,
                    displayPeakNits = config.displayPeakNits,
                    paperWhiteNits = config.paperWhiteNits,
                    debandStrength = config.debandStrength,
                    ditherStrength = config.ditherStrength,
                    sourceRange = config.sourceRange.ordinal,
                    ycbcrMatrix = config.sourceMatrix.ordinal,
                    chromaLocation = config.chromaLocation.ordinal,
                    rotationDegrees = config.geometry.normalizedRotationDegrees,
                    pixelAspectRatio =
                        config.geometry.pixelAspectRatioNumerator.toFloat() /
                            config.geometry.pixelAspectRatioDenominator.coerceAtLeast(1).toFloat(),
                    cropLeft = config.geometry.cropLeft.toFloat(),
                    cropTop = config.geometry.cropTop.toFloat(),
                    cropRight = config.geometry.cropRight.toFloat(),
                    cropBottom = config.geometry.cropBottom.toFloat(),
                    dynamicMetadataEnabled = config.hdr10PlusSceneMetadata != null,
                    dynamicAnchorCount = config.hdr10PlusSceneMetadata?.bezierAnchors?.size ?: 0,
                    dynamicTargetNits = config.hdr10PlusSceneMetadata?.targetedDisplayMaximumNits ?: 0f,
                    dynamicScenePeakNits = config.hdr10PlusSceneMetadata?.scenePeakNits ?: 0f,
                    dynamicAverageNits = config.hdr10PlusSceneMetadata?.averageMaxRgbNits ?: 0f,
                    dynamicKneeX = config.hdr10PlusSceneMetadata?.kneePointX ?: 0f,
                    dynamicKneeY = config.hdr10PlusSceneMetadata?.kneePointY ?: 0f,
                    dynamicAnchorMean = config.hdr10PlusSceneMetadata?.bezierAnchors?.average()?.toFloat() ?: 0f,
                    hdrMetadata = config.nativeHdrMetadata(),
                )
            }.getOrDefault(0L)
        } else {
            0L
        }

    fun rendererFeatureMask(renderer: Long): Long =
        if (libraryLoaded && renderer != 0L) runCatching { nativeRendererFeatureMask(renderer) }.getOrDefault(0L) else 0L

    fun lastGpuDurationNs(renderer: Long): Long =
        if (libraryLoaded && renderer != 0L) runCatching { nativeLastGpuDurationNs(renderer) }.getOrDefault(0L) else 0L

    fun destroyRenderer(renderer: Long) {
        if (libraryLoaded && renderer != 0L) runCatching { nativeDestroyRenderer(renderer) }
    }

    private external fun nativeGpuApiVersion(): Int

    private external fun nativeProbeGpuFeatures(): Long

    private external fun nativeCreateRenderer(
        surface: Surface,
        outputTransfer: Int,
    ): Long

    private external fun nativeRenderHardwareBuffer(
        renderer: Long,
        hardwareBuffer: HardwareBuffer,
        sourceTransfer: Int,
        outputTransfer: Int,
        sourcePrimaries: Int,
        outputPrimaries: Int,
        scalingFilter: Int,
        sourceBitDepth: Int,
        frameIndex: Int,
        toneMapEnabled: Boolean,
        sourcePeakNits: Float,
        displayPeakNits: Float,
        paperWhiteNits: Float,
        debandStrength: Float,
        ditherStrength: Float,
        sourceRange: Int,
        ycbcrMatrix: Int,
        chromaLocation: Int,
        rotationDegrees: Int,
        pixelAspectRatio: Float,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        dynamicMetadataEnabled: Boolean,
        dynamicAnchorCount: Int,
        dynamicTargetNits: Float,
        dynamicScenePeakNits: Float,
        dynamicAverageNits: Float,
        dynamicKneeX: Float,
        dynamicKneeY: Float,
        dynamicAnchorMean: Float,
        hdrMetadata: FloatArray,
    ): Long

    private external fun nativeRendererFeatureMask(renderer: Long): Long

    private external fun nativeLastGpuDurationNs(renderer: Long): Long

    private external fun nativeDestroyRenderer(renderer: Long)
}

/**
 * New packages keep the Vulkan executor isolated from FFmpeg/libbluray. The demux fallback keeps
 * older all-in-one development AARs usable while the standalone carrier is rolling forward.
 */
private val YCORE_GPU_NATIVE_LIBRARIES = arrayOf("ycore_gpu", "ycore_demux")

private fun YGpuColorPipelineConfig.nativeHdrMetadata(): FloatArray {
    val metadata = hdrStaticMetadata ?: return FloatArray(12)
    return floatArrayOf(
        metadata.redX / 50_000f,
        metadata.redY / 50_000f,
        metadata.greenX / 50_000f,
        metadata.greenY / 50_000f,
        metadata.blueX / 50_000f,
        metadata.blueY / 50_000f,
        metadata.whiteX / 50_000f,
        metadata.whiteY / 50_000f,
        metadata.maxDisplayLuminance.toFloat(),
        metadata.minDisplayLuminance / 10_000f,
        metadata.maxContentLightLevel.toFloat(),
        metadata.maxFrameAverageLightLevel.toFloat(),
    )
}
