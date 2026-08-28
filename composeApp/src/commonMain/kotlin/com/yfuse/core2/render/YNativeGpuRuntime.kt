package com.yfuse.core2.render

/** Native capability bits shared with ycore_gpu_capability.h. */
enum class YNativeGpuFeature(
    val mask: Long,
) {
    VulkanLoader(1L shl 0),
    VulkanInstance(1L shl 1),
    PhysicalDevice(1L shl 2),
    LogicalDevice(1L shl 3),
    Swapchain(1L shl 4),
    HardwareBuffer(1L shl 5),
    SamplerYcbcrConversion(1L shl 6),
    HardwareBufferImported(1L shl 7),
    SwapchainPresented(1L shl 8),
    DecodedFramePresented(1L shl 9),
    OutputMeasured(1L shl 10),
    ImageReaderDecodedFrame(1L shl 11),
    P010Input(1L shl 12),
    HdrSwapchain(1L shl 13),
    Bt2390Shader(1L shl 14),
    GamutMappingShader(1L shl 15),
    HighQualityScalingShader(1L shl 16),
    DebandDitherShader(1L shl 17),
    DisplayTiming(1L shl 18),
}

data class YNativeGpuRuntimeProbe(
    val platformApiLevel: Int,
    val nativeApiVersion: Int,
    val featureMask: Long,
) {
    fun supports(feature: YNativeGpuFeature): Boolean = featureMask and feature.mask != 0L

    /** Enough native functionality to create a surface warm-up executor, but not to route media. */
    val supportsWarmup: Boolean
        get() =
            platformApiLevel >= MIN_ANDROID_HARDWARE_BUFFER_API &&
                nativeApiVersion == NATIVE_GPU_API_VERSION &&
                WARMUP_FEATURES.all(::supports)

    /** A real decoded frame and measured output are mandatory before Vulkan becomes a media route. */
    val canClaimNativeVulkan: Boolean
        get() =
            supportsWarmup &&
                VERIFIED_OUTPUT_FEATURES.all(::supports) &&
                COLOR_PIPELINE_FEATURES.all(::supports)

    /** Allows an opt-in measurement run; this is deliberately weaker than a publishable claim. */
    val canAttemptNativeVulkan: Boolean
        get() = supportsWarmup && STATIC_COLOR_PIPELINE_FEATURES.all(::supports)

    val canProcessHdr: Boolean
        get() = canClaimNativeVulkan && supports(YNativeGpuFeature.P010Input)

    fun toGpuCapabilities(): YGpuCapabilities =
        YGpuCapabilities(
            backends = if (supportsWarmup) setOf(YGpuBackend.Vulkan) else emptySet(),
            nativeVulkanExecutorVerified = canClaimNativeVulkan,
            toneMappers = if (supports(YNativeGpuFeature.Bt2390Shader)) setOf(YToneMapper.Bt2390) else emptySet(),
            scalingFilters =
                if (supports(YNativeGpuFeature.HighQualityScalingShader)) {
                    setOf(YScalingFilter.Bilinear, YScalingFilter.Bicubic, YScalingFilter.Lanczos)
                } else {
                    setOf(YScalingFilter.Bilinear)
                },
            supportsHdrInput = supports(YNativeGpuFeature.P010Input),
            supportsHdrOutput = supports(YNativeGpuFeature.HdrSwapchain),
            supportsTenBitOutput = supports(YNativeGpuFeature.HdrSwapchain),
        )

    fun firstMissingRequirement(): YNativeGpuRequirement? =
        when {
            platformApiLevel < MIN_ANDROID_HARDWARE_BUFFER_API -> YNativeGpuRequirement.AndroidApi
            nativeApiVersion != NATIVE_GPU_API_VERSION -> YNativeGpuRequirement.NativeApi
            !supports(YNativeGpuFeature.VulkanLoader) -> YNativeGpuRequirement.VulkanLoader
            !supports(YNativeGpuFeature.VulkanInstance) -> YNativeGpuRequirement.VulkanInstance
            !supports(YNativeGpuFeature.PhysicalDevice) -> YNativeGpuRequirement.PhysicalDevice
            !supports(YNativeGpuFeature.LogicalDevice) -> YNativeGpuRequirement.LogicalDevice
            !supports(YNativeGpuFeature.Swapchain) -> YNativeGpuRequirement.Swapchain
            !supports(YNativeGpuFeature.HardwareBuffer) -> YNativeGpuRequirement.HardwareBuffer
            !supports(YNativeGpuFeature.SamplerYcbcrConversion) -> YNativeGpuRequirement.SamplerYcbcrConversion
            !supports(YNativeGpuFeature.HardwareBufferImported) -> YNativeGpuRequirement.HardwareBufferImport
            !supports(YNativeGpuFeature.SwapchainPresented) -> YNativeGpuRequirement.SwapchainPresentation
            !supports(YNativeGpuFeature.DecodedFramePresented) -> YNativeGpuRequirement.DecodedFramePresentation
            !supports(YNativeGpuFeature.OutputMeasured) -> YNativeGpuRequirement.MeasuredOutput
            !COLOR_PIPELINE_FEATURES.all(::supports) -> YNativeGpuRequirement.ColorPipeline
            else -> null
        }
}

enum class YNativeGpuRequirement {
    AndroidApi,
    NativeApi,
    VulkanLoader,
    VulkanInstance,
    PhysicalDevice,
    LogicalDevice,
    Swapchain,
    HardwareBuffer,
    SamplerYcbcrConversion,
    HardwareBufferImport,
    SwapchainPresentation,
    DecodedFramePresentation,
    MeasuredOutput,
    ColorPipeline,
}

private val WARMUP_FEATURES =
    setOf(
        YNativeGpuFeature.VulkanLoader,
        YNativeGpuFeature.VulkanInstance,
        YNativeGpuFeature.PhysicalDevice,
        YNativeGpuFeature.LogicalDevice,
        YNativeGpuFeature.Swapchain,
        YNativeGpuFeature.HardwareBuffer,
        YNativeGpuFeature.SamplerYcbcrConversion,
        YNativeGpuFeature.HardwareBufferImported,
    )
private val VERIFIED_OUTPUT_FEATURES =
    setOf(
        YNativeGpuFeature.SwapchainPresented,
        YNativeGpuFeature.DecodedFramePresented,
        YNativeGpuFeature.OutputMeasured,
    )

private val COLOR_PIPELINE_FEATURES =
    setOf(
        YNativeGpuFeature.ImageReaderDecodedFrame,
        YNativeGpuFeature.Bt2390Shader,
        YNativeGpuFeature.GamutMappingShader,
        YNativeGpuFeature.HighQualityScalingShader,
        YNativeGpuFeature.DebandDitherShader,
        YNativeGpuFeature.DisplayTiming,
    )

private val STATIC_COLOR_PIPELINE_FEATURES =
    setOf(
        YNativeGpuFeature.Bt2390Shader,
        YNativeGpuFeature.GamutMappingShader,
        YNativeGpuFeature.HighQualityScalingShader,
        YNativeGpuFeature.DebandDitherShader,
        YNativeGpuFeature.DisplayTiming,
    )

const val NATIVE_GPU_API_VERSION = 2
const val MIN_ANDROID_HARDWARE_BUFFER_API = 28
