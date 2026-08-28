#ifndef YFUSE_YCORE_GPU_CAPABILITY_H
#define YFUSE_YCORE_GPU_CAPABILITY_H

#include <cstdint>

namespace ycore::gpu {

constexpr std::uint64_t kVulkanLoader = 1ULL << 0;
constexpr std::uint64_t kVulkanInstance = 1ULL << 1;
constexpr std::uint64_t kPhysicalDevice = 1ULL << 2;
constexpr std::uint64_t kLogicalDevice = 1ULL << 3;
constexpr std::uint64_t kSwapchain = 1ULL << 4;
constexpr std::uint64_t kHardwareBuffer = 1ULL << 5;
constexpr std::uint64_t kSamplerYcbcrConversion = 1ULL << 6;
constexpr std::uint64_t kHardwareBufferImported = 1ULL << 7;
constexpr std::uint64_t kSwapchainPresented = 1ULL << 8;
constexpr std::uint64_t kDecodedFramePresented = 1ULL << 9;
constexpr std::uint64_t kOutputMeasured = 1ULL << 10;

constexpr std::uint64_t kWarmupRequirements =
    kVulkanLoader | kVulkanInstance | kPhysicalDevice | kLogicalDevice | kSwapchain |
    kHardwareBuffer | kSamplerYcbcrConversion | kHardwareBufferImported;
constexpr std::uint64_t kVerifiedOutputRequirements =
    kSwapchainPresented | kDecodedFramePresented | kOutputMeasured;

constexpr bool supports_warmup(std::uint64_t features) {
    return (features & kWarmupRequirements) == kWarmupRequirements;
}

constexpr bool can_claim_native_vulkan(std::uint64_t features) {
    return supports_warmup(features) &&
           (features & kVerifiedOutputRequirements) == kVerifiedOutputRequirements;
}

}  // namespace ycore::gpu

#endif
