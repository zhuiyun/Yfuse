#include "ycore_gpu_capability.h"

#include <cassert>

int main() {
    using namespace ycore::gpu;

    static_assert(supports_warmup(kWarmupRequirements));
    static_assert(!can_claim_native_vulkan(kWarmupRequirements));
    static_assert(can_claim_native_vulkan(
        kWarmupRequirements | kVerifiedOutputRequirements | kColorPipelineRequirements |
        kImageReaderDecodedFrame | kDisplayTiming));

    assert(!supports_warmup(kWarmupRequirements & ~kHardwareBufferImported));
    assert(!can_claim_native_vulkan(kWarmupRequirements | kSwapchainPresented));
    static_assert((kColorPipelineRequirements & kVerifiedOutputRequirements) == 0);
    static_assert((kColorPipelineRequirements & kBt2390Shader) != 0);
    return 0;
}
