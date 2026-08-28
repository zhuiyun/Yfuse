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
        get() = supportsWarmup && VERIFIED_OUTPUT_FEATURES.all(::supports)

    fun toGpuCapabilities(): YGpuCapabilities =
        YGpuCapabilities(
            backends = if (supportsWarmup) setOf(YGpuBackend.Vulkan) else emptySet(),
            nativeVulkanExecutorVerified = canClaimNativeVulkan,
            // The first native milestone proves zero-copy import and presentation only. HDR
            // processing is enabled later when its shaders and measured device evidence exist.
            toneMappers = emptySet(),
            scalingFilters = setOf(YScalingFilter.Bilinear),
            supportsHdrInput = false,
            supportsHdrOutput = false,
            supportsTenBitOutput = false,
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

const val NATIVE_GPU_API_VERSION = 1
const val MIN_ANDROID_HARDWARE_BUFFER_API = 28
