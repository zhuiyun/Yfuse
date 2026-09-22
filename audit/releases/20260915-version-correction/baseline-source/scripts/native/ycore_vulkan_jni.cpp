#include <jni.h>

#include <android/hardware_buffer.h>

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <vector>

#include "ycore_gpu_capability.h"

namespace {

constexpr jint kNativeGpuApiVersion = 2;
constexpr uint32_t kProbeBufferWidth = 64;
constexpr uint32_t kProbeBufferHeight = 64;

bool has_extension(
    const std::vector<VkExtensionProperties>& extensions,
    const char* name) {
    return std::any_of(extensions.begin(), extensions.end(), [&](const auto& extension) {
        return std::strcmp(extension.extensionName, name) == 0;
    });
}

std::vector<VkExtensionProperties> instance_extensions() {
    uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS || count == 0) {
        return {};
    }
    std::vector<VkExtensionProperties> values(count);
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, values.data()) != VK_SUCCESS) return {};
    values.resize(count);
    return values;
}

std::vector<VkExtensionProperties> device_extensions(VkPhysicalDevice physical_device) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(physical_device, nullptr, &count, nullptr) != VK_SUCCESS ||
        count == 0) {
        return {};
    }
    std::vector<VkExtensionProperties> values(count);
    if (vkEnumerateDeviceExtensionProperties(
            physical_device, nullptr, &count, values.data()) != VK_SUCCESS) {
        return {};
    }
    values.resize(count);
    return values;
}

int graphics_queue_family(VkPhysicalDevice physical_device) {
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical_device, &count, nullptr);
    if (count == 0) return -1;
    std::vector<VkQueueFamilyProperties> properties(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physical_device, &count, properties.data());
    for (uint32_t index = 0; index < count; ++index) {
        if (properties[index].queueCount > 0 &&
            (properties[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 &&
            properties[index].timestampValidBits > 0) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

uint32_t select_memory_type(
    VkPhysicalDevice physical_device,
    uint32_t allowed_types) {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physical_device, &properties);
    for (uint32_t index = 0; index < properties.memoryTypeCount; ++index) {
        if ((allowed_types & (1U << index)) != 0) return index;
    }
    return UINT32_MAX;
}

struct ProbeResources {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    AHardwareBuffer* hardware_buffer = nullptr;

    ~ProbeResources() {
        if (device != VK_NULL_HANDLE) {
            if (image != VK_NULL_HANDLE) vkDestroyImage(device, image, nullptr);
            if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (hardware_buffer != nullptr) AHardwareBuffer_release(hardware_buffer);
        if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    }
};

bool import_probe_hardware_buffer(ProbeResources* resources) {
    if (resources == nullptr || resources->device == VK_NULL_HANDLE ||
        resources->physical_device == VK_NULL_HANDLE) {
        return false;
    }
    AHardwareBuffer_Desc descriptor{};
    descriptor.width = kProbeBufferWidth;
    descriptor.height = kProbeBufferHeight;
    descriptor.layers = 1;
    descriptor.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    descriptor.usage =
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&descriptor, &resources->hardware_buffer) != 0 ||
        resources->hardware_buffer == nullptr) {
        return false;
    }

    const auto get_properties =
        reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(resources->device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (get_properties == nullptr) return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID format_properties{};
    format_properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID buffer_properties{};
    buffer_properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    buffer_properties.pNext = &format_properties;
    if (get_properties(resources->device, resources->hardware_buffer, &buffer_properties) != VK_SUCCESS) {
        return false;
    }

    VkExternalFormatANDROID external_format{};
    external_format.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    external_format.externalFormat = format_properties.externalFormat;
    VkExternalMemoryImageCreateInfo external_memory{};
    external_memory.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    external_memory.handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    if (format_properties.format == VK_FORMAT_UNDEFINED) external_memory.pNext = &external_format;

    VkImageCreateInfo image_info{};
    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.pNext = &external_memory;
    image_info.imageType = VK_IMAGE_TYPE_2D;
    image_info.format = format_properties.format;
    image_info.extent = {kProbeBufferWidth, kProbeBufferHeight, 1};
    image_info.mipLevels = 1;
    image_info.arrayLayers = 1;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(resources->device, &image_info, nullptr, &resources->image) != VK_SUCCESS) {
        return false;
    }

    VkMemoryRequirements memory_requirements{};
    vkGetImageMemoryRequirements(resources->device, resources->image, &memory_requirements);
    const uint32_t memory_type =
        select_memory_type(
            resources->physical_device,
            memory_requirements.memoryTypeBits & buffer_properties.memoryTypeBits);
    if (memory_type == UINT32_MAX) return false;

    VkMemoryDedicatedAllocateInfo dedicated_info{};
    dedicated_info.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated_info.image = resources->image;
    VkImportAndroidHardwareBufferInfoANDROID import_info{};
    import_info.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import_info.pNext = &dedicated_info;
    import_info.buffer = resources->hardware_buffer;
    VkMemoryAllocateInfo allocate_info{};
    allocate_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate_info.pNext = &import_info;
    allocate_info.allocationSize = buffer_properties.allocationSize;
    allocate_info.memoryTypeIndex = memory_type;
    if (vkAllocateMemory(resources->device, &allocate_info, nullptr, &resources->memory) != VK_SUCCESS) {
        return false;
    }
    return vkBindImageMemory(resources->device, resources->image, resources->memory, 0) == VK_SUCCESS;
}

std::uint64_t probe_gpu_features() {
    using namespace ycore::gpu;
    std::uint64_t features = kVulkanLoader;
    ProbeResources resources;

    const auto available_instance_extensions = instance_extensions();
    std::vector<const char*> enabled_instance_extensions;
    if (has_extension(
            available_instance_extensions,
            VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME)) {
        enabled_instance_extensions.push_back(
            VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
    }

    VkApplicationInfo application_info{};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "YCore GPU probe";
    application_info.applicationVersion = 1;
    application_info.pEngineName = "YCore";
    application_info.engineVersion = 1;
    application_info.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo instance_info{};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &application_info;
    instance_info.enabledExtensionCount =
        static_cast<uint32_t>(enabled_instance_extensions.size());
    instance_info.ppEnabledExtensionNames = enabled_instance_extensions.data();
    if (vkCreateInstance(&instance_info, nullptr, &resources.instance) != VK_SUCCESS) return features;
    features |= kVulkanInstance;

    uint32_t physical_device_count = 0;
    if (vkEnumeratePhysicalDevices(resources.instance, &physical_device_count, nullptr) != VK_SUCCESS ||
        physical_device_count == 0) {
        return features;
    }
    std::vector<VkPhysicalDevice> physical_devices(physical_device_count);
    if (vkEnumeratePhysicalDevices(
            resources.instance, &physical_device_count, physical_devices.data()) != VK_SUCCESS) {
        return features;
    }

    int queue_family = -1;
    std::vector<const char*> enabled_device_extensions;
    for (VkPhysicalDevice candidate : physical_devices) {
        const auto extensions = device_extensions(candidate);
        if (!has_extension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ||
            !has_extension(
                extensions,
                VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
            !has_extension(extensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
            continue;
        }
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(candidate, &properties);
        const bool core_ycbcr = VK_VERSION_MAJOR(properties.apiVersion) > 1 ||
            (VK_VERSION_MAJOR(properties.apiVersion) == 1 &&
             VK_VERSION_MINOR(properties.apiVersion) >= 1);
        if (!core_ycbcr) continue;
        VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr_features{};
        ycbcr_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
        VkPhysicalDeviceFeatures2 features2{};
        features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        features2.pNext = &ycbcr_features;
        auto get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(resources.instance, "vkGetPhysicalDeviceFeatures2"));
        if (get_features2 == nullptr) {
            get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                vkGetInstanceProcAddr(resources.instance, "vkGetPhysicalDeviceFeatures2KHR"));
        }
        if (get_features2 == nullptr) continue;
        get_features2(candidate, &features2);
        if (ycbcr_features.samplerYcbcrConversion != VK_TRUE) continue;
        queue_family = graphics_queue_family(candidate);
        if (queue_family < 0) continue;

        resources.physical_device = candidate;
        enabled_device_extensions = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        };
        break;
    }
    if (resources.physical_device == VK_NULL_HANDLE) return features;
    features |= kPhysicalDevice | kSwapchain | kSamplerYcbcrConversion;

    constexpr float queue_priority = 1.0F;
    VkDeviceQueueCreateInfo queue_info{};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = static_cast<uint32_t>(queue_family);
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &queue_priority;
    VkDeviceCreateInfo device_info{};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    VkPhysicalDeviceSamplerYcbcrConversionFeatures enabled_ycbcr{};
    enabled_ycbcr.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
    enabled_ycbcr.samplerYcbcrConversion = VK_TRUE;
    device_info.pNext = &enabled_ycbcr;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = static_cast<uint32_t>(enabled_device_extensions.size());
    device_info.ppEnabledExtensionNames = enabled_device_extensions.data();
    if (vkCreateDevice(
            resources.physical_device, &device_info, nullptr, &resources.device) != VK_SUCCESS) {
        return features;
    }
    features |= kLogicalDevice | kHardwareBuffer;
    features |= kBt2390Shader | kGamutMappingShader | kHighQualityScalingShader | kDebandDitherShader;
    if (has_extension(
            device_extensions(resources.physical_device),
            VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)) {
        features |= kDisplayTiming;
    }
    if (import_probe_hardware_buffer(&resources)) features |= kHardwareBufferImported;
    return features;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_yfuse_core2_android_AndroidYCoreGpuNativeBridge_nativeGpuApiVersion(
    JNIEnv*,
    jobject) {
    return kNativeGpuApiVersion;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yfuse_core2_android_AndroidYCoreGpuNativeBridge_nativeProbeGpuFeatures(
    JNIEnv*,
    jobject) {
    return static_cast<jlong>(probe_gpu_features());
}
