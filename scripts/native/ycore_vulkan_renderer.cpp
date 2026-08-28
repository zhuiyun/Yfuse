#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <cstddef>
#include <vector>

#include "ycore_gpu_capability.h"
#include "ycore_gpu_shaders.inc"

namespace {

constexpr uint32_t kMinimumMeasuredFrames = 24;
constexpr uint32_t kMinimumTimestampSamples = 12;
constexpr uint32_t kMaximumSlowMeasuredFrames = 2;
constexpr size_t kMaximumPerformanceSamples = 64;
constexpr uint64_t kFenceTimeoutNs = 250'000'000ULL;
constexpr uint64_t kMaximumMeasuredGpuFrameNs = 50'000'000ULL;
constexpr uint64_t kMaximumAverageGpuFrameNs = 25'000'000ULL;
constexpr uint64_t kMaximumP95GpuFrameNs = 40'000'000ULL;
constexpr size_t kMaximumImportedFrameCacheSize = 12;
constexpr size_t kFramesInFlight = 3;
constexpr int32_t kTransferSdr = 0;
constexpr int32_t kTransferPq = 1;
constexpr int32_t kTransferHlg = 2;

struct FrameParameters {
    int32_t source_transfer;
    int32_t output_transfer;
    int32_t source_primaries;
    int32_t output_primaries;
    int32_t scaling_filter;
    int32_t source_bit_depth;
    int32_t frame_index;
    int32_t tone_map_enabled;
    float source_peak_nits;
    float display_peak_nits;
    float paper_white_nits;
    float deband_strength;
    float dither_strength;
    float output_width;
    float output_height;
    int32_t source_range;
    int32_t ycbcr_matrix;
    int32_t chroma_location;
    int32_t rotation_degrees;
    float pixel_aspect_ratio;
    float crop_left;
    float crop_top;
    float crop_right;
    float crop_bottom;
    int32_t dynamic_metadata_enabled;
    int32_t dynamic_anchor_count;
    float dynamic_target_nits;
    float dynamic_scene_peak_nits;
    float dynamic_average_nits;
    float dynamic_knee_x;
    float dynamic_knee_y;
    float dynamic_anchor_mean;
    float hdr_red_x;
    float hdr_red_y;
    float hdr_green_x;
    float hdr_green_y;
    float hdr_blue_x;
    float hdr_blue_y;
    float hdr_white_x;
    float hdr_white_y;
    float hdr_max_luminance;
    float hdr_min_luminance;
    float hdr_max_cll;
    float hdr_max_fall;
};
constexpr uint32_t kShaderPushConstantBytes = offsetof(FrameParameters, hdr_red_x);
static_assert(kShaderPushConstantBytes == 128);
static_assert(sizeof(FrameParameters) == 176);

bool contains_extension(const std::vector<VkExtensionProperties>& values, const char* name) {
    return std::any_of(values.begin(), values.end(), [&](const auto& value) {
        return std::strcmp(value.extensionName, name) == 0;
    });
}

std::vector<VkExtensionProperties> enumerate_instance_extensions() {
    uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS) return {};
    std::vector<VkExtensionProperties> values(count);
    if (count > 0 && vkEnumerateInstanceExtensionProperties(nullptr, &count, values.data()) != VK_SUCCESS) return {};
    values.resize(count);
    return values;
}

std::vector<VkExtensionProperties> enumerate_device_extensions(VkPhysicalDevice device) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) return {};
    std::vector<VkExtensionProperties> values(count);
    if (count > 0 && vkEnumerateDeviceExtensionProperties(device, nullptr, &count, values.data()) != VK_SUCCESS) return {};
    values.resize(count);
    return values;
}

uint32_t select_memory_type(VkPhysicalDevice physical_device, uint32_t mask) {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physical_device, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((mask & (1U << i)) != 0) return i;
    }
    return UINT32_MAX;
}

VkShaderModule create_shader(VkDevice device, const uint32_t* words, size_t byte_count) {
    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = byte_count;
    info.pCode = words;
    VkShaderModule module = VK_NULL_HANDLE;
    return vkCreateShaderModule(device, &info, nullptr, &module) == VK_SUCCESS ? module : VK_NULL_HANDLE;
}

struct ImportedFrame {
    VkDevice device = VK_NULL_HANDLE;
    AHardwareBuffer* buffer = nullptr;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t hardware_buffer_format = 0;

    ~ImportedFrame() {
        if (device != VK_NULL_HANDLE) {
            if (view != VK_NULL_HANDLE) vkDestroyImageView(device, view, nullptr);
            if (image != VK_NULL_HANDLE) vkDestroyImage(device, image, nullptr);
            if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, nullptr);
        }
        if (buffer != nullptr) AHardwareBuffer_release(buffer);
    }
};

struct CachedImportedFrame {
    uint64_t identity = 0;
    uint64_t last_use = 0;
    std::shared_ptr<ImportedFrame> frame;
};

struct FrameSlot {
    VkCommandBuffer command_buffer = VK_NULL_HANDLE;
    VkSemaphore image_available = VK_NULL_HANDLE;
    VkSemaphore render_finished = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkDescriptorSet descriptor_set = VK_NULL_HANDLE;
    std::shared_ptr<ImportedFrame> imported_frame;
    bool query_pending = false;
};

uint64_t hardware_buffer_identity(AHardwareBuffer* buffer) {
    using GetId = int (*)(const AHardwareBuffer*, uint64_t*);
    static auto get_id = reinterpret_cast<GetId>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId"));
    uint64_t identity = 0;
    if (get_id != nullptr && get_id(buffer, &identity) == 0 && identity != 0) return identity;
    // API 26-30 has no stable-id entry point. ImageReader returns the same native buffer handle
    // while a slot is recycled, and the acquired reference prevents this address being reused.
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(buffer));
}

class VulkanRenderer {
public:
    VulkanRenderer(JNIEnv* env, jobject surface, int32_t output_transfer) {
        output_transfer_ = output_transfer;
        window_ = ANativeWindow_fromSurface(env, surface);
        if (window_ == nullptr || !create(output_transfer)) destroy();
    }

    ~VulkanRenderer() { destroy(); }

    bool ready() const {
        return device_ != VK_NULL_HANDLE && swapchain_ != VK_NULL_HANDLE &&
            frame_slots_[0].command_buffer != VK_NULL_HANDLE;
    }

    uint64_t render(JNIEnv* env, jobject hardware_buffer, const FrameParameters& parameters) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!ready() || hardware_buffer == nullptr) return feature_mask_;
        if (surface_extent_changed() && !recreate_swapchain()) return feature_mask_;
        auto frame = import_frame(env, hardware_buffer, parameters);
        if (!frame || pipeline_ == VK_NULL_HANDLE) return feature_mask_;
        feature_mask_ |= ycore::gpu::kHardwareBufferImported | ycore::gpu::kImageReaderDecodedFrame;
        if (frame->hardware_buffer_format == AHARDWAREBUFFER_FORMAT_YCbCr_P010) {
            feature_mask_ |= ycore::gpu::kP010Input;
        }
        update_hdr_metadata(parameters);

        const size_t slot_index = next_frame_slot_++ % kFramesInFlight;
        FrameSlot& slot = frame_slots_[slot_index];
        if (vkWaitForFences(device_, 1, &slot.fence, VK_TRUE, kFenceTimeoutNs) != VK_SUCCESS) {
            return feature_mask_;
        }
        collect_completed_slot(slot, slot_index);
        slot.imported_frame.reset();
        uint32_t image_index = 0;
        VkResult acquire = vkAcquireNextImageKHR(
            device_, swapchain_, kFenceTimeoutNs, slot.image_available, VK_NULL_HANDLE, &image_index);
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
            recreate_swapchain();
            return feature_mask_;
        }
        if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) return feature_mask_;
        const bool recreate_after_present = acquire == VK_SUBOPTIMAL_KHR;

        VkDescriptorImageInfo descriptor{};
        descriptor.sampler = ycbcr_sampler_;
        descriptor.imageView = frame->view;
        descriptor.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = slot.descriptor_set;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &descriptor;
        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);

        vkResetCommandBuffer(slot.command_buffer, 0);
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(slot.command_buffer, &begin) != VK_SUCCESS) return feature_mask_;
        const uint32_t first_query = static_cast<uint32_t>(slot_index * 2);
        vkCmdResetQueryPool(slot.command_buffer, query_pool_, first_query, 2);
        vkCmdWriteTimestamp(slot.command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, query_pool_, first_query);

        VkImageMemoryBarrier input_barrier{};
        input_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        input_barrier.srcAccessMask = 0;
        input_barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        input_barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        input_barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        input_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        input_barrier.dstQueueFamilyIndex = queue_family_;
        input_barrier.image = frame->image;
        input_barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        input_barrier.subresourceRange.levelCount = 1;
        input_barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(
            slot.command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &input_barrier);

        VkClearValue clear{};
        VkRenderPassBeginInfo render_begin{};
        render_begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        render_begin.renderPass = render_pass_;
        render_begin.framebuffer = framebuffers_[image_index];
        render_begin.renderArea.extent = extent_;
        render_begin.clearValueCount = 1;
        render_begin.pClearValues = &clear;
        vkCmdBeginRenderPass(slot.command_buffer, &render_begin, VK_SUBPASS_CONTENTS_INLINE);
        VkViewport viewport{};
        viewport.width = static_cast<float>(extent_.width);
        viewport.height = static_cast<float>(extent_.height);
        viewport.maxDepth = 1.0F;
        VkRect2D scissor{{0, 0}, extent_};
        vkCmdSetViewport(slot.command_buffer, 0, 1, &viewport);
        vkCmdSetScissor(slot.command_buffer, 0, 1, &scissor);
        vkCmdBindPipeline(slot.command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
        vkCmdBindDescriptorSets(
            slot.command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout_, 0, 1,
            &slot.descriptor_set, 0, nullptr);
        FrameParameters shader_parameters = parameters;
        shader_parameters.output_width = static_cast<float>(extent_.width);
        shader_parameters.output_height = static_cast<float>(extent_.height);
        vkCmdPushConstants(
            slot.command_buffer, pipeline_layout_, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
            kShaderPushConstantBytes, &shader_parameters);
        vkCmdDraw(slot.command_buffer, 3, 1, 0, 0);
        vkCmdEndRenderPass(slot.command_buffer);
        VkImageMemoryBarrier release_barrier{};
        release_barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        release_barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        release_barrier.dstAccessMask = 0;
        release_barrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        release_barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        release_barrier.srcQueueFamilyIndex = queue_family_;
        release_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        release_barrier.image = frame->image;
        release_barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        release_barrier.subresourceRange.levelCount = 1;
        release_barrier.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(
            slot.command_buffer, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 1, &release_barrier);
        vkCmdWriteTimestamp(slot.command_buffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, query_pool_, first_query + 1);
        if (vkEndCommandBuffer(slot.command_buffer) != VK_SUCCESS) return feature_mask_;

        VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &slot.image_available;
        submit.pWaitDstStageMask = &wait_stage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &slot.command_buffer;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &slot.render_finished;
        if (vkResetFences(device_, 1, &slot.fence) != VK_SUCCESS) return feature_mask_;
        slot.imported_frame = frame;
        if (vkQueueSubmit(queue_, 1, &submit, slot.fence) != VK_SUCCESS) {
            slot.imported_frame.reset();
            restore_signaled_fence(slot);
            return feature_mask_;
        }
        slot.query_pending = true;

        VkPresentTimeGOOGLE present_time{};
        present_time.presentID = ++present_id_;
        present_time.desiredPresentTime = 0;
        VkPresentTimesInfoGOOGLE present_times{};
        present_times.sType = VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
        present_times.swapchainCount = 1;
        present_times.pTimes = &present_time;
        VkPresentInfoKHR present{};
        present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present.pNext = display_timing_ ? &present_times : nullptr;
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &slot.render_finished;
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain_;
        present.pImageIndices = &image_index;
        VkResult presented = vkQueuePresentKHR(queue_, &present);
        if (presented == VK_ERROR_OUT_OF_DATE_KHR) {
            recreate_swapchain();
            return feature_mask_;
        }
        if (presented != VK_SUCCESS && presented != VK_SUBOPTIMAL_KHR) return feature_mask_;
        feature_mask_ |= ycore::gpu::kSwapchainPresented | ycore::gpu::kDecodedFramePresented;
        ++presented_frames_;

        collect_presentation_timing();
        if ((feature_mask_ & ycore::gpu::kOutputMeasured) == 0 &&
            presented_frames_ >= kMinimumMeasuredFrames &&
            timestamp_samples_ >= kMinimumTimestampSamples &&
            slow_timestamp_samples_ <= kMaximumSlowMeasuredFrames &&
            measured_shader_performance_passes() &&
            display_timing_ && past_presentations_ > 0) {
            feature_mask_ |= ycore::gpu::kOutputMeasured;
        }
        if (recreate_after_present || presented == VK_SUBOPTIMAL_KHR) recreate_swapchain();
        return feature_mask_;
    }

    uint64_t feature_mask() const { return feature_mask_; }
    uint64_t last_gpu_duration_ns() const { return last_gpu_duration_ns_; }

private:
    bool create(int32_t output_transfer) {
        const auto instance_extensions = enumerate_instance_extensions();
        if (!contains_extension(instance_extensions, VK_KHR_SURFACE_EXTENSION_NAME) ||
            !contains_extension(instance_extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) return false;
        std::vector<const char*> enabled_instance{VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "YCore Vulkan renderer";
        app.pEngineName = "YCore";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instance_info{};
        instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instance_info.pApplicationInfo = &app;
        instance_info.enabledExtensionCount = static_cast<uint32_t>(enabled_instance.size());
        instance_info.ppEnabledExtensionNames = enabled_instance.data();
        if (vkCreateInstance(&instance_info, nullptr, &instance_) != VK_SUCCESS) return false;

        VkAndroidSurfaceCreateInfoKHR surface_info{};
        surface_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surface_info.window = window_;
        if (vkCreateAndroidSurfaceKHR(instance_, &surface_info, nullptr, &surface_) != VK_SUCCESS) return false;

        uint32_t device_count = 0;
        if (vkEnumeratePhysicalDevices(instance_, &device_count, nullptr) != VK_SUCCESS || device_count == 0) return false;
        std::vector<VkPhysicalDevice> devices(device_count);
        vkEnumeratePhysicalDevices(instance_, &device_count, devices.data());
        for (auto candidate : devices) {
            auto extensions = enumerate_device_extensions(candidate);
            if (!contains_extension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ||
                !contains_extension(extensions, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
                !contains_extension(extensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) continue;
            VkPhysicalDeviceProperties device_properties{};
            vkGetPhysicalDeviceProperties(candidate, &device_properties);
            if (device_properties.apiVersion < VK_API_VERSION_1_1) continue;
            VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr_features{};
            ycbcr_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
            VkPhysicalDeviceFeatures2 features{};
            features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            features.pNext = &ycbcr_features;
            auto get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                vkGetInstanceProcAddr(instance_, "vkGetPhysicalDeviceFeatures2"));
            if (get_features2 == nullptr) {
                get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                    vkGetInstanceProcAddr(instance_, "vkGetPhysicalDeviceFeatures2KHR"));
            }
            if (get_features2 == nullptr) continue;
            get_features2(candidate, &features);
            if (ycbcr_features.samplerYcbcrConversion != VK_TRUE) continue;
            uint32_t queue_count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queue_count, nullptr);
            std::vector<VkQueueFamilyProperties> queues(queue_count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queue_count, queues.data());
            for (uint32_t i = 0; i < queue_count; ++i) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface_, &present);
                if (
                    present &&
                    (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 &&
                    queues[i].timestampValidBits > 0
                ) {
                    physical_device_ = candidate;
                    queue_family_ = i;
                    display_timing_ = contains_extension(extensions, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
                    hdr_metadata_ = contains_extension(extensions, VK_EXT_HDR_METADATA_EXTENSION_NAME);
                    break;
                }
            }
            if (physical_device_ != VK_NULL_HANDLE) break;
        }
        if (physical_device_ == VK_NULL_HANDLE) return false;

        std::vector<const char*> device_extensions{
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        };
        if (display_timing_) device_extensions.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
        if (hdr_metadata_) device_extensions.push_back(VK_EXT_HDR_METADATA_EXTENSION_NAME);
        float priority = 1.0F;
        VkDeviceQueueCreateInfo queue_info{};
        queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queue_info.queueFamilyIndex = queue_family_;
        queue_info.queueCount = 1;
        queue_info.pQueuePriorities = &priority;
        VkDeviceCreateInfo device_info{};
        device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        VkPhysicalDeviceSamplerYcbcrConversionFeatures enabled_ycbcr{};
        enabled_ycbcr.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
        enabled_ycbcr.samplerYcbcrConversion = VK_TRUE;
        device_info.pNext = &enabled_ycbcr;
        device_info.queueCreateInfoCount = 1;
        device_info.pQueueCreateInfos = &queue_info;
        device_info.enabledExtensionCount = static_cast<uint32_t>(device_extensions.size());
        device_info.ppEnabledExtensionNames = device_extensions.data();
        if (vkCreateDevice(physical_device_, &device_info, nullptr, &device_) != VK_SUCCESS) return false;
        create_ycbcr_conversion_ = reinterpret_cast<PFN_vkCreateSamplerYcbcrConversion>(
            vkGetDeviceProcAddr(device_, "vkCreateSamplerYcbcrConversion"));
        destroy_ycbcr_conversion_ = reinterpret_cast<PFN_vkDestroySamplerYcbcrConversion>(
            vkGetDeviceProcAddr(device_, "vkDestroySamplerYcbcrConversion"));
        if (create_ycbcr_conversion_ == nullptr || destroy_ycbcr_conversion_ == nullptr) return false;
        vkGetDeviceQueue(device_, queue_family_, 0, &queue_);
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physical_device_, &properties);
        timestamp_period_ns_ = properties.limits.timestampPeriod;
        feature_mask_ = ycore::gpu::kVulkanLoader | ycore::gpu::kVulkanInstance |
            ycore::gpu::kPhysicalDevice | ycore::gpu::kLogicalDevice | ycore::gpu::kSwapchain |
            ycore::gpu::kHardwareBuffer | ycore::gpu::kSamplerYcbcrConversion |
            ycore::gpu::kBt2390Shader | ycore::gpu::kGamutMappingShader |
            ycore::gpu::kHighQualityScalingShader | ycore::gpu::kDebandDitherShader;
        if (display_timing_) feature_mask_ |= ycore::gpu::kDisplayTiming;
        return create_swapchain(output_transfer) && create_commands();
    }

    bool create_swapchain(int32_t output_transfer, VkSwapchainKHR old_swapchain = VK_NULL_HANDLE) {
        if (output_transfer != kTransferSdr && output_transfer != kTransferPq && output_transfer != kTransferHlg) {
            return false;
        }
        VkSurfaceCapabilitiesKHR capabilities{};
        if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_device_, surface_, &capabilities) != VK_SUCCESS) return false;
        uint32_t format_count = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &format_count, nullptr);
        if (format_count == 0) return false;
        std::vector<VkSurfaceFormatKHR> formats(format_count);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &format_count, formats.data());
        feature_mask_ &= ~ycore::gpu::kHdrSwapchain;
        auto chosen = formats.front();
        if (output_transfer != kTransferSdr) {
            const VkColorSpaceKHR requested_color_space = output_transfer == kTransferHlg
                ? VK_COLOR_SPACE_HDR10_HLG_EXT : VK_COLOR_SPACE_HDR10_ST2084_EXT;
            auto hdr = std::find_if(formats.begin(), formats.end(), [&](const auto& format) {
                return (format.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
                        format.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32) &&
                    format.colorSpace == requested_color_space;
            });
            if (hdr == formats.end()) return false;
            chosen = *hdr;
            feature_mask_ |= ycore::gpu::kHdrSwapchain;
        }
        if ((feature_mask_ & ycore::gpu::kHdrSwapchain) == 0) {
            auto sdr = std::find_if(formats.begin(), formats.end(), [](const auto& format) {
                return format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM;
            });
            if (sdr != formats.end()) chosen = *sdr;
        }
        swapchain_format_ = chosen.format;
        swapchain_color_space_ = chosen.colorSpace;
        extent_ = capabilities.currentExtent;
        if (extent_.width == UINT32_MAX) {
            extent_.width = std::clamp(static_cast<uint32_t>(std::max(ANativeWindow_getWidth(window_), 1)),
                                       capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
            extent_.height = std::clamp(static_cast<uint32_t>(std::max(ANativeWindow_getHeight(window_), 1)),
                                        capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
        }
        uint32_t image_count = std::max(2U, capabilities.minImageCount + 1);
        if (capabilities.maxImageCount > 0) image_count = std::min(image_count, capabilities.maxImageCount);
        VkCompositeAlphaFlagBitsKHR composite = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        for (auto candidate : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                               VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
            if ((capabilities.supportedCompositeAlpha & candidate) != 0) { composite = candidate; break; }
        }
        VkSwapchainCreateInfoKHR info{};
        info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        info.surface = surface_;
        info.minImageCount = image_count;
        info.imageFormat = chosen.format;
        info.imageColorSpace = chosen.colorSpace;
        info.imageExtent = extent_;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.preTransform = capabilities.currentTransform;
        info.compositeAlpha = composite;
        info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        info.clipped = VK_TRUE;
        info.oldSwapchain = old_swapchain;
        if (vkCreateSwapchainKHR(device_, &info, nullptr, &swapchain_) != VK_SUCCESS) return false;
        uint32_t count = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, nullptr);
        swapchain_images_.resize(count);
        vkGetSwapchainImagesKHR(device_, swapchain_, &count, swapchain_images_.data());
        swapchain_views_.resize(count);
        for (uint32_t i = 0; i < count; ++i) {
            VkImageViewCreateInfo view{};
            view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            view.image = swapchain_images_[i];
            view.viewType = VK_IMAGE_VIEW_TYPE_2D;
            view.format = swapchain_format_;
            view.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            view.subresourceRange.levelCount = 1;
            view.subresourceRange.layerCount = 1;
            if (vkCreateImageView(device_, &view, nullptr, &swapchain_views_[i]) != VK_SUCCESS) return false;
        }
        return true;
    }

    bool surface_extent_changed() const {
        if (window_ == nullptr || extent_.width == 0 || extent_.height == 0) return false;
        const int32_t width = ANativeWindow_getWidth(window_);
        const int32_t height = ANativeWindow_getHeight(window_);
        return width > 0 && height > 0 &&
            (static_cast<uint32_t>(width) != extent_.width || static_cast<uint32_t>(height) != extent_.height);
    }

    bool recreate_swapchain() {
        if (device_ == VK_NULL_HANDLE || surface_ == VK_NULL_HANDLE) return false;
        if (vkDeviceWaitIdle(device_) != VK_SUCCESS) return false;
        for (auto& slot : frame_slots_) {
            slot.imported_frame.reset();
            slot.query_pending = false;
        }
        destroy_pipeline_resources();
        const VkSwapchainKHR old_swapchain = swapchain_;
        auto old_views = std::move(swapchain_views_);
        swapchain_ = VK_NULL_HANDLE;
        swapchain_images_.clear();
        framebuffers_.clear();
        const bool created = create_swapchain(output_transfer_, old_swapchain);
        for (auto view : old_views) {
            if (view != VK_NULL_HANDLE) vkDestroyImageView(device_, view, nullptr);
        }
        if (old_swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, old_swapchain, nullptr);
        feature_mask_ &= ~(ycore::gpu::kSwapchainPresented | ycore::gpu::kDecodedFramePresented |
            ycore::gpu::kOutputMeasured);
        presented_frames_ = timestamp_samples_ = slow_timestamp_samples_ = past_presentations_ = 0;
        timestamp_durations_ns_.clear();
        if (!created) return false;
        return !pipeline_initialization_attempted_ || create_pipeline();
    }

    bool create_pipeline() {
        VkAttachmentDescription attachment{};
        attachment.format = swapchain_format_;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &reference;
        VkRenderPassCreateInfo render_info{};
        render_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        render_info.attachmentCount = 1;
        render_info.pAttachments = &attachment;
        render_info.subpassCount = 1;
        render_info.pSubpasses = &subpass;
        if (vkCreateRenderPass(device_, &render_info, nullptr, &render_pass_) != VK_SUCCESS) return false;
        framebuffers_.resize(swapchain_views_.size());
        for (size_t i = 0; i < swapchain_views_.size(); ++i) {
            VkFramebufferCreateInfo info{};
            info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            info.renderPass = render_pass_;
            info.attachmentCount = 1;
            info.pAttachments = &swapchain_views_[i];
            info.width = extent_.width;
            info.height = extent_.height;
            info.layers = 1;
            if (vkCreateFramebuffer(device_, &info, nullptr, &framebuffers_[i]) != VK_SUCCESS) return false;
        }
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.descriptorCount = 1;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        binding.pImmutableSamplers = &ycbcr_sampler_;
        VkDescriptorSetLayoutCreateInfo layout_info{};
        layout_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layout_info.bindingCount = 1;
        layout_info.pBindings = &binding;
        if (vkCreateDescriptorSetLayout(device_, &layout_info, nullptr, &descriptor_layout_) != VK_SUCCESS) return false;
        VkPushConstantRange push{};
        push.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        push.size = kShaderPushConstantBytes;
        VkPipelineLayoutCreateInfo pipeline_layout_info{};
        pipeline_layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pipeline_layout_info.setLayoutCount = 1;
        pipeline_layout_info.pSetLayouts = &descriptor_layout_;
        pipeline_layout_info.pushConstantRangeCount = 1;
        pipeline_layout_info.pPushConstantRanges = &push;
        if (vkCreatePipelineLayout(device_, &pipeline_layout_info, nullptr, &pipeline_layout_) != VK_SUCCESS) return false;
        VkShaderModule vertex = create_shader(device_, kYCoreFullscreenVertexShader, sizeof(kYCoreFullscreenVertexShader));
        VkShaderModule fragment = create_shader(device_, kYCoreVideoFragmentShader, sizeof(kYCoreVideoFragmentShader));
        if (vertex == VK_NULL_HANDLE || fragment == VK_NULL_HANDLE) return false;
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vertex;
        stages[0].pName = "main";
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fragment;
        stages[1].pName = "main";
        VkPipelineVertexInputStateCreateInfo vertex_input{};
        vertex_input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo assembly{};
        assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewport{};
        viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewport.viewportCount = 1;
        viewport.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo raster{};
        raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        raster.polygonMode = VK_POLYGON_MODE_FILL;
        raster.cullMode = VK_CULL_MODE_NONE;
        raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        raster.lineWidth = 1.0F;
        VkPipelineMultisampleStateCreateInfo multisample{};
        multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blend_attachment{};
        blend_attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend{};
        blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blend.attachmentCount = 1;
        blend.pAttachments = &blend_attachment;
        std::array<VkDynamicState, 2> dynamics{VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic{};
        dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamic.dynamicStateCount = static_cast<uint32_t>(dynamics.size());
        dynamic.pDynamicStates = dynamics.data();
        VkGraphicsPipelineCreateInfo pipeline_info{};
        pipeline_info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipeline_info.stageCount = 2;
        pipeline_info.pStages = stages;
        pipeline_info.pVertexInputState = &vertex_input;
        pipeline_info.pInputAssemblyState = &assembly;
        pipeline_info.pViewportState = &viewport;
        pipeline_info.pRasterizationState = &raster;
        pipeline_info.pMultisampleState = &multisample;
        pipeline_info.pColorBlendState = &blend;
        pipeline_info.pDynamicState = &dynamic;
        pipeline_info.layout = pipeline_layout_;
        pipeline_info.renderPass = render_pass_;
        VkResult result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipeline_info, nullptr, &pipeline_);
        vkDestroyShaderModule(device_, vertex, nullptr);
        vkDestroyShaderModule(device_, fragment, nullptr);
        if (result != VK_SUCCESS) return false;
        // A multi-planar immutable YCbCr sampler may consume more than one underlying descriptor.
        VkDescriptorPoolSize pool_size{
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            static_cast<uint32_t>(kFramesInFlight * 4),
        };
        VkDescriptorPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pool_info.maxSets = static_cast<uint32_t>(kFramesInFlight);
        pool_info.poolSizeCount = 1;
        pool_info.pPoolSizes = &pool_size;
        if (vkCreateDescriptorPool(device_, &pool_info, nullptr, &descriptor_pool_) != VK_SUCCESS) return false;
        VkDescriptorSetAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate.descriptorPool = descriptor_pool_;
        std::array<VkDescriptorSetLayout, kFramesInFlight> layouts{};
        layouts.fill(descriptor_layout_);
        std::array<VkDescriptorSet, kFramesInFlight> descriptor_sets{};
        allocate.descriptorSetCount = static_cast<uint32_t>(descriptor_sets.size());
        allocate.pSetLayouts = layouts.data();
        if (vkAllocateDescriptorSets(device_, &allocate, descriptor_sets.data()) != VK_SUCCESS) return false;
        for (size_t i = 0; i < kFramesInFlight; ++i) {
            frame_slots_[i].descriptor_set = descriptor_sets[i];
        }
        return true;
    }

    bool create_commands() {
        VkCommandPoolCreateInfo pool{};
        pool.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pool.queueFamilyIndex = queue_family_;
        if (vkCreateCommandPool(device_, &pool, nullptr, &command_pool_) != VK_SUCCESS) return false;
        VkCommandBufferAllocateInfo command{};
        command.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        command.commandPool = command_pool_;
        command.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        std::array<VkCommandBuffer, kFramesInFlight> command_buffers{};
        command.commandBufferCount = static_cast<uint32_t>(command_buffers.size());
        if (vkAllocateCommandBuffers(device_, &command, command_buffers.data()) != VK_SUCCESS) return false;
        VkSemaphoreCreateInfo semaphore{};
        semaphore.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkFenceCreateInfo fence{};
        fence.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        for (size_t i = 0; i < kFramesInFlight; ++i) {
            auto& slot = frame_slots_[i];
            slot.command_buffer = command_buffers[i];
            if (vkCreateSemaphore(device_, &semaphore, nullptr, &slot.image_available) != VK_SUCCESS ||
                vkCreateSemaphore(device_, &semaphore, nullptr, &slot.render_finished) != VK_SUCCESS ||
                vkCreateFence(device_, &fence, nullptr, &slot.fence) != VK_SUCCESS) return false;
        }
        VkQueryPoolCreateInfo query{};
        query.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        query.queryType = VK_QUERY_TYPE_TIMESTAMP;
        query.queryCount = static_cast<uint32_t>(kFramesInFlight * 2);
        return vkCreateQueryPool(device_, &query, nullptr, &query_pool_) == VK_SUCCESS;
    }

    void collect_completed_slot(FrameSlot& slot, size_t slot_index) {
        if (!slot.query_pending) return;
        uint64_t timestamps[2]{};
        const uint32_t first_query = static_cast<uint32_t>(slot_index * 2);
        if (vkGetQueryPoolResults(
                device_, query_pool_, first_query, 2, sizeof(timestamps), timestamps,
                sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] > timestamps[0]) {
            ++timestamp_samples_;
            last_gpu_duration_ns_ = static_cast<uint64_t>(
                static_cast<double>(timestamps[1] - timestamps[0]) * timestamp_period_ns_);
            timestamp_durations_ns_.push_back(last_gpu_duration_ns_);
            if (timestamp_durations_ns_.size() > kMaximumPerformanceSamples) {
                timestamp_durations_ns_.erase(timestamp_durations_ns_.begin());
            }
            slow_timestamp_samples_ = static_cast<uint32_t>(std::count_if(
                timestamp_durations_ns_.begin(), timestamp_durations_ns_.end(),
                [](uint64_t duration) { return duration > kMaximumMeasuredGpuFrameNs; }));
        }
        slot.query_pending = false;
    }

    bool measured_shader_performance_passes() const {
        if (timestamp_durations_ns_.size() < kMinimumTimestampSamples) return false;
        uint64_t total = 0;
        for (uint64_t duration : timestamp_durations_ns_) total += duration;
        const uint64_t average = total / timestamp_durations_ns_.size();
        auto ordered = timestamp_durations_ns_;
        std::sort(ordered.begin(), ordered.end());
        const size_t p95_index = std::min(
            ordered.size() - 1,
            static_cast<size_t>(std::ceil(static_cast<double>(ordered.size()) * 0.95)) - 1);
        return average <= kMaximumAverageGpuFrameNs && ordered[p95_index] <= kMaximumP95GpuFrameNs;
    }

    void restore_signaled_fence(FrameSlot& slot) {
        if (slot.fence != VK_NULL_HANDLE) vkDestroyFence(device_, slot.fence, nullptr);
        VkFenceCreateInfo fence{};
        fence.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        slot.fence = VK_NULL_HANDLE;
        vkCreateFence(device_, &fence, nullptr, &slot.fence);
        slot.query_pending = false;
    }

    std::shared_ptr<ImportedFrame> import_frame(JNIEnv* env, jobject object, const FrameParameters& parameters) {
        AHardwareBuffer* raw = AHardwareBuffer_fromHardwareBuffer(env, object);
        if (raw == nullptr) return nullptr;
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(raw, &desc);
        const uint64_t identity = hardware_buffer_identity(raw);
        const auto cached = std::find_if(imported_frame_cache_.begin(), imported_frame_cache_.end(),
            [&](const auto& entry) {
                return entry.identity == identity && entry.frame != nullptr &&
                    entry.frame->width == desc.width && entry.frame->height == desc.height &&
                    entry.frame->hardware_buffer_format == desc.format;
            });
        if (cached != imported_frame_cache_.end()) {
            cached->last_use = ++imported_frame_use_counter_;
            return cached->frame;
        }
        AHardwareBuffer_acquire(raw);
        auto frame = std::make_shared<ImportedFrame>();
        frame->device = device_;
        frame->buffer = raw;
        frame->width = desc.width;
        frame->height = desc.height;
        frame->hardware_buffer_format = desc.format;
        auto get_properties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(device_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (get_properties == nullptr) return nullptr;
        VkAndroidHardwareBufferFormatPropertiesANDROID format{};
        format.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID properties{};
        properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        properties.pNext = &format;
        if (get_properties(device_, raw, &properties) != VK_SUCCESS) return nullptr;
        if (!ensure_ycbcr_pipeline(format, parameters)) return nullptr;
        VkExternalFormatANDROID external{};
        external.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
        external.externalFormat = format.externalFormat;
        VkExternalMemoryImageCreateInfo external_memory{};
        external_memory.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        external_memory.pNext = format.format == VK_FORMAT_UNDEFINED ? &external : nullptr;
        external_memory.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo image{};
        image.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        image.pNext = &external_memory;
        image.imageType = VK_IMAGE_TYPE_2D;
        image.format = format.format;
        image.extent = {desc.width, desc.height, 1};
        image.mipLevels = 1;
        image.arrayLayers = 1;
        image.samples = VK_SAMPLE_COUNT_1_BIT;
        image.tiling = VK_IMAGE_TILING_OPTIMAL;
        image.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateImage(device_, &image, nullptr, &frame->image) != VK_SUCCESS) return nullptr;
        VkMemoryRequirements memory_requirements{};
        vkGetImageMemoryRequirements(device_, frame->image, &memory_requirements);
        uint32_t memory_type = select_memory_type(
            physical_device_, properties.memoryTypeBits & memory_requirements.memoryTypeBits);
        if (memory_type == UINT32_MAX) return nullptr;
        VkMemoryDedicatedAllocateInfo dedicated{};
        dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
        dedicated.image = frame->image;
        VkImportAndroidHardwareBufferInfoANDROID imported{};
        imported.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        imported.pNext = &dedicated;
        imported.buffer = raw;
        VkMemoryAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocate.pNext = &imported;
        allocate.allocationSize = properties.allocationSize;
        allocate.memoryTypeIndex = memory_type;
        if (vkAllocateMemory(device_, &allocate, nullptr, &frame->memory) != VK_SUCCESS ||
            vkBindImageMemory(device_, frame->image, frame->memory, 0) != VK_SUCCESS) return nullptr;
        VkSamplerYcbcrConversionInfo conversion_info{};
        conversion_info.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
        conversion_info.conversion = ycbcr_conversion_;
        VkImageViewCreateInfo view{};
        view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        view.pNext = &conversion_info;
        view.image = frame->image;
        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view.format = format.format;
        view.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        view.subresourceRange.levelCount = 1;
        view.subresourceRange.layerCount = 1;
        if (vkCreateImageView(device_, &view, nullptr, &frame->view) != VK_SUCCESS) return nullptr;
        imported_frame_cache_.push_back({identity, ++imported_frame_use_counter_, frame});
        if (imported_frame_cache_.size() > kMaximumImportedFrameCacheSize) {
            const auto oldest = std::min_element(
                imported_frame_cache_.begin(), imported_frame_cache_.end(),
                [](const auto& left, const auto& right) { return left.last_use < right.last_use; });
            if (oldest != imported_frame_cache_.end()) imported_frame_cache_.erase(oldest);
        }
        return frame;
    }

    bool ensure_ycbcr_pipeline(
        const VkAndroidHardwareBufferFormatPropertiesANDROID& format,
        const FrameParameters& parameters) {
        const VkSamplerYcbcrModelConversion model =
            parameters.ycbcr_matrix == 1 ? VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601 :
            parameters.ycbcr_matrix == 3 ? VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020 :
            parameters.ycbcr_matrix == 4 ? VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY :
            VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
        const VkSamplerYcbcrRange range = parameters.source_range == 2
            ? VK_SAMPLER_YCBCR_RANGE_ITU_FULL : VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
        VkChromaLocation x_chroma = format.suggestedXChromaOffset;
        VkChromaLocation y_chroma = format.suggestedYChromaOffset;
        if (parameters.chroma_location == 1 || parameters.chroma_location == 3 ||
            parameters.chroma_location == 5) x_chroma = VK_CHROMA_LOCATION_COSITED_EVEN;
        if (parameters.chroma_location == 2 || parameters.chroma_location == 4) {
            x_chroma = VK_CHROMA_LOCATION_MIDPOINT;
        }
        if (parameters.chroma_location == 2) y_chroma = VK_CHROMA_LOCATION_MIDPOINT;
        if (parameters.chroma_location == 3 || parameters.chroma_location == 4) {
            y_chroma = VK_CHROMA_LOCATION_COSITED_EVEN;
        }
        if (pipeline_initialization_attempted_) {
            return pipeline_ != VK_NULL_HANDLE && input_format_ == format.format &&
                input_external_format_ == format.externalFormat && input_ycbcr_model_ == model &&
                input_ycbcr_range_ == range && input_x_chroma_ == x_chroma &&
                input_y_chroma_ == y_chroma;
        }
        pipeline_initialization_attempted_ = true;
        input_format_ = format.format;
        input_external_format_ = format.externalFormat;
        VkExternalFormatANDROID external{};
        external.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
        external.externalFormat = format.externalFormat;
        VkSamplerYcbcrConversionCreateInfo conversion{};
        conversion.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
        conversion.pNext = format.format == VK_FORMAT_UNDEFINED ? &external : nullptr;
        conversion.format = format.format;
        conversion.ycbcrModel = model;
        conversion.ycbcrRange = range;
        conversion.components = format.samplerYcbcrConversionComponents;
        conversion.xChromaOffset = x_chroma;
        conversion.yChromaOffset = y_chroma;
        conversion.chromaFilter = VK_FILTER_LINEAR;
        if (create_ycbcr_conversion_(device_, &conversion, nullptr, &ycbcr_conversion_) != VK_SUCCESS) {
            return false;
        }
        input_ycbcr_model_ = model;
        input_ycbcr_range_ = range;
        input_x_chroma_ = x_chroma;
        input_y_chroma_ = y_chroma;
        VkSamplerYcbcrConversionInfo conversion_info{};
        conversion_info.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
        conversion_info.conversion = ycbcr_conversion_;
        VkSamplerCreateInfo sampler{};
        sampler.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sampler.pNext = &conversion_info;
        sampler.magFilter = VK_FILTER_LINEAR;
        sampler.minFilter = VK_FILTER_LINEAR;
        sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampler.maxLod = 1.0F;
        if (vkCreateSampler(device_, &sampler, nullptr, &ycbcr_sampler_) != VK_SUCCESS) return false;
        return create_pipeline();
    }

    void collect_presentation_timing() {
        if (!display_timing_) return;
        auto get_timing = reinterpret_cast<PFN_vkGetPastPresentationTimingGOOGLE>(
            vkGetDeviceProcAddr(device_, "vkGetPastPresentationTimingGOOGLE"));
        if (get_timing == nullptr) return;
        uint32_t count = 0;
        if (get_timing(device_, swapchain_, &count, nullptr) != VK_SUCCESS || count == 0) return;
        std::vector<VkPastPresentationTimingGOOGLE> timings(count);
        if (get_timing(device_, swapchain_, &count, timings.data()) == VK_SUCCESS) past_presentations_ += count;
    }

    void update_hdr_metadata(const FrameParameters& parameters) {
        if (!hdr_metadata_ || (feature_mask_ & ycore::gpu::kHdrSwapchain) == 0) return;
        auto set_metadata = reinterpret_cast<PFN_vkSetHdrMetadataEXT>(
            vkGetDeviceProcAddr(device_, "vkSetHdrMetadataEXT"));
        if (set_metadata == nullptr) return;
        VkHdrMetadataEXT metadata{};
        metadata.sType = VK_STRUCTURE_TYPE_HDR_METADATA_EXT;
        if (parameters.hdr_red_x > 0.0F && parameters.hdr_green_x > 0.0F &&
            parameters.hdr_blue_x > 0.0F) {
            metadata.displayPrimaryRed = {parameters.hdr_red_x, parameters.hdr_red_y};
            metadata.displayPrimaryGreen = {parameters.hdr_green_x, parameters.hdr_green_y};
            metadata.displayPrimaryBlue = {parameters.hdr_blue_x, parameters.hdr_blue_y};
        } else if (parameters.output_primaries == 1) {
            metadata.displayPrimaryRed = {0.708F, 0.292F};
            metadata.displayPrimaryGreen = {0.170F, 0.797F};
            metadata.displayPrimaryBlue = {0.131F, 0.046F};
        } else {
            metadata.displayPrimaryRed = {0.680F, 0.320F};
            metadata.displayPrimaryGreen = {0.265F, 0.690F};
            metadata.displayPrimaryBlue = {0.150F, 0.060F};
        }
        metadata.whitePoint = parameters.hdr_white_x > 0.0F
            ? VkXYColorEXT{parameters.hdr_white_x, parameters.hdr_white_y}
            : VkXYColorEXT{0.3127F, 0.3290F};
        metadata.maxLuminance = parameters.hdr_max_luminance > 0.0F
            ? parameters.hdr_max_luminance : std::clamp(parameters.display_peak_nits, 80.0F, 10'000.0F);
        metadata.minLuminance = parameters.hdr_min_luminance > 0.0F
            ? parameters.hdr_min_luminance : 0.0001F;
        metadata.maxContentLightLevel = parameters.dynamic_metadata_enabled != 0 &&
                parameters.dynamic_scene_peak_nits > 0.0F
            ? std::clamp(parameters.dynamic_scene_peak_nits, 1.0F, 10'000.0F)
            : parameters.hdr_max_cll > 0.0F
                ? parameters.hdr_max_cll : std::clamp(parameters.source_peak_nits, 80.0F, 10'000.0F);
        metadata.maxFrameAverageLightLevel = parameters.hdr_max_fall > 0.0F
            ? parameters.hdr_max_fall : std::min(metadata.maxContentLightLevel, 400.0F);
        set_metadata(device_, 1, &swapchain_, &metadata);
    }

    void destroy_pipeline_resources() {
        if (device_ == VK_NULL_HANDLE) return;
        if (descriptor_pool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device_, descriptor_pool_, nullptr);
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline_, nullptr);
        if (pipeline_layout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, pipeline_layout_, nullptr);
        if (descriptor_layout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device_, descriptor_layout_, nullptr);
        for (auto framebuffer : framebuffers_) {
            if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, framebuffer, nullptr);
        }
        if (render_pass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, render_pass_, nullptr);
        descriptor_pool_ = VK_NULL_HANDLE;
        pipeline_ = VK_NULL_HANDLE;
        pipeline_layout_ = VK_NULL_HANDLE;
        descriptor_layout_ = VK_NULL_HANDLE;
        render_pass_ = VK_NULL_HANDLE;
        framebuffers_.clear();
        for (auto& slot : frame_slots_) slot.descriptor_set = VK_NULL_HANDLE;
    }

    void destroy() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        if (device_ != VK_NULL_HANDLE) {
            imported_frame_cache_.clear();
            if (query_pool_ != VK_NULL_HANDLE) vkDestroyQueryPool(device_, query_pool_, nullptr);
            for (auto& slot : frame_slots_) {
                slot.imported_frame.reset();
                if (slot.fence != VK_NULL_HANDLE) vkDestroyFence(device_, slot.fence, nullptr);
                if (slot.image_available != VK_NULL_HANDLE) vkDestroySemaphore(device_, slot.image_available, nullptr);
                if (slot.render_finished != VK_NULL_HANDLE) vkDestroySemaphore(device_, slot.render_finished, nullptr);
            }
            if (command_pool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, command_pool_, nullptr);
            destroy_pipeline_resources();
            if (ycbcr_sampler_ != VK_NULL_HANDLE) vkDestroySampler(device_, ycbcr_sampler_, nullptr);
            if (ycbcr_conversion_ != VK_NULL_HANDLE && destroy_ycbcr_conversion_ != nullptr) {
                destroy_ycbcr_conversion_(device_, ycbcr_conversion_, nullptr);
            }
            for (auto view : swapchain_views_) if (view != VK_NULL_HANDLE) vkDestroyImageView(device_, view, nullptr);
            if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            vkDestroyDevice(device_, nullptr);
        }
        if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
        if (window_ != nullptr) ANativeWindow_release(window_);
        device_ = VK_NULL_HANDLE;
        swapchain_ = VK_NULL_HANDLE;
        window_ = nullptr;
    }

    mutable std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queue_family_ = 0;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchain_format_ = VK_FORMAT_UNDEFINED;
    VkColorSpaceKHR swapchain_color_space_ = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkExtent2D extent_{};
    std::vector<VkImage> swapchain_images_;
    std::vector<VkImageView> swapchain_views_;
    std::vector<VkFramebuffer> framebuffers_;
    VkRenderPass render_pass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptor_layout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptor_pool_ = VK_NULL_HANDLE;
    VkSamplerYcbcrConversion ycbcr_conversion_ = VK_NULL_HANDLE;
    PFN_vkCreateSamplerYcbcrConversion create_ycbcr_conversion_ = nullptr;
    PFN_vkDestroySamplerYcbcrConversion destroy_ycbcr_conversion_ = nullptr;
    VkSampler ycbcr_sampler_ = VK_NULL_HANDLE;
    VkFormat input_format_ = VK_FORMAT_UNDEFINED;
    uint64_t input_external_format_ = 0;
    VkSamplerYcbcrModelConversion input_ycbcr_model_ = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
    VkSamplerYcbcrRange input_ycbcr_range_ = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    VkChromaLocation input_x_chroma_ = VK_CHROMA_LOCATION_MIDPOINT;
    VkChromaLocation input_y_chroma_ = VK_CHROMA_LOCATION_MIDPOINT;
    bool pipeline_initialization_attempted_ = false;
    std::vector<CachedImportedFrame> imported_frame_cache_;
    uint64_t imported_frame_use_counter_ = 0;
    VkCommandPool command_pool_ = VK_NULL_HANDLE;
    std::array<FrameSlot, kFramesInFlight> frame_slots_{};
    size_t next_frame_slot_ = 0;
    VkQueryPool query_pool_ = VK_NULL_HANDLE;
    bool display_timing_ = false;
    bool hdr_metadata_ = false;
    int32_t output_transfer_ = kTransferSdr;
    double timestamp_period_ns_ = 0.0;
    uint32_t present_id_ = 0;
    uint32_t presented_frames_ = 0;
    uint32_t timestamp_samples_ = 0;
    uint32_t slow_timestamp_samples_ = 0;
    uint32_t past_presentations_ = 0;
    uint64_t last_gpu_duration_ns_ = 0;
    std::vector<uint64_t> timestamp_durations_ns_;
    uint64_t feature_mask_ = 0;
};

VulkanRenderer* from_handle(jlong handle) {
    return reinterpret_cast<VulkanRenderer*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_yfuse_core2_android_AndroidYCoreGpuNativeBridge_nativeCreateRenderer(
    JNIEnv* env, jobject, jobject surface, jint output_transfer) {
    auto renderer = std::make_unique<VulkanRenderer>(env, surface, output_transfer);
    if (!renderer->ready()) return 0;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer.release()));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yfuse_core2_android_AndroidYCoreGpuNativeBridge_nativeRenderHardwareBuffer(
    JNIEnv* env, jobject, jlong handle, jobject hardware_buffer,
    jint source_transfer, jint output_transfer, jint source_primaries, jint output_primaries,
    jint scaling_filter, jint source_bit_depth, jint frame_index, jboolean tone_map_enabled,
    jfloat source_peak_nits, jfloat display_peak_nits, jfloat paper_white_nits,
    jfloat deband_strength, jfloat dither_strength, jint source_range, jint ycbcr_matrix,
    jint chroma_location, jint rotation_degrees, jfloat pixel_aspect_ratio,
    jfloat crop_left, jfloat crop_top, jfloat crop_right, jfloat crop_bottom,
    jboolean dynamic_metadata_enabled, jint dynamic_anchor_count, jfloat dynamic_target_nits,
    jfloat dynamic_scene_peak_nits, jfloat dynamic_average_nits, jfloat dynamic_knee_x,
    jfloat dynamic_knee_y, jfloat dynamic_anchor_mean,
    jfloatArray hdr_metadata) {
    auto* renderer = from_handle(handle);
    if (renderer == nullptr) return 0;
    FrameParameters parameters{};
    parameters.source_transfer = source_transfer;
    parameters.output_transfer = output_transfer;
    parameters.source_primaries = source_primaries;
    parameters.output_primaries = output_primaries;
    parameters.scaling_filter = scaling_filter;
    parameters.source_bit_depth = source_bit_depth;
    parameters.frame_index = frame_index;
    parameters.tone_map_enabled = tone_map_enabled == JNI_TRUE ? 1 : 0;
    parameters.source_peak_nits = source_peak_nits;
    parameters.display_peak_nits = display_peak_nits;
    parameters.paper_white_nits = paper_white_nits;
    parameters.deband_strength = deband_strength;
    parameters.dither_strength = dither_strength;
    parameters.source_range = source_range;
    parameters.ycbcr_matrix = ycbcr_matrix;
    parameters.chroma_location = chroma_location;
    parameters.rotation_degrees = rotation_degrees;
    parameters.pixel_aspect_ratio = pixel_aspect_ratio;
    parameters.crop_left = crop_left;
    parameters.crop_top = crop_top;
    parameters.crop_right = crop_right;
    parameters.crop_bottom = crop_bottom;
    parameters.dynamic_metadata_enabled = dynamic_metadata_enabled == JNI_TRUE ? 1 : 0;
    parameters.dynamic_anchor_count = dynamic_anchor_count;
    parameters.dynamic_target_nits = dynamic_target_nits;
    parameters.dynamic_scene_peak_nits = dynamic_scene_peak_nits;
    parameters.dynamic_average_nits = dynamic_average_nits;
    parameters.dynamic_knee_x = dynamic_knee_x;
    parameters.dynamic_knee_y = dynamic_knee_y;
    parameters.dynamic_anchor_mean = dynamic_anchor_mean;
    if (hdr_metadata != nullptr && env->GetArrayLength(hdr_metadata) >= 12) {
        env->GetFloatArrayRegion(hdr_metadata, 0, 12, &parameters.hdr_red_x);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    return static_cast<jlong>(renderer->render(env, hardware_buffer, parameters));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yfuse_core2_android_AndroidYCoreGpuNativeBridge_nativeRendererFeatureMask(
    JNIEnv*, jobject, jlong handle) {
    auto* renderer = from_handle(handle);
    return renderer == nullptr ? 0 : static_cast<jlong>(renderer->feature_mask());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yfuse_core2_android_AndroidYCoreGpuNativeBridge_nativeLastGpuDurationNs(
    JNIEnv*, jobject, jlong handle) {
    auto* renderer = from_handle(handle);
    return renderer == nullptr ? 0 : static_cast<jlong>(renderer->last_gpu_duration_ns());
}

extern "C" JNIEXPORT void JNICALL
Java_com_yfuse_core2_android_AndroidYCoreGpuNativeBridge_nativeDestroyRenderer(
    JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}
