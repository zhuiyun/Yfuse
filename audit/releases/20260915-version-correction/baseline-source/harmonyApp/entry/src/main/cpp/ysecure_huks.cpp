#include "ycore/ycore.h"

#include <cstdlib>
#include <cstring>
#include <random>

#if defined(__OHOS__) && __has_include("asset/asset_api.h")
#include "asset/asset_api.h"
#define YFUSE_HAS_ASSET_STORE 1
#else
#define YFUSE_HAS_ASSET_STORE 0
#endif

namespace {

#if YFUSE_HAS_ASSET_STORE
Asset_Blob blob(const char *value) {
    return {static_cast<uint32_t>(std::strlen(value)), reinterpret_cast<uint8_t *>(const_cast<char *>(value))};
}

int translated(int32_t value) {
    if (value == ASSET_SUCCESS) return 0;
    if (value == ASSET_NOT_FOUND) return -2;
    return -5;
}
#endif

}  // namespace

extern "C" {

struct ysecure_read_result {
    int status;
    char *value;
};

/* Asset Store encrypts ASSET_TAG_SECRET and protects its keys through the platform security stack. */
YCORE_API int ysecure_put(const char *alias, const char *value) {
    if (alias == nullptr || value == nullptr || alias[0] == '\0' || value[0] == '\0') return -1;
#if YFUSE_HAS_ASSET_STORE
    if (std::strlen(alias) > 256 || std::strlen(value) > 1024) return -1;
    Asset_Blob alias_blob = blob(alias);
    Asset_Blob secret_blob = blob(value);
    Asset_Attr query[1]{};
    query[0].tag = ASSET_TAG_ALIAS;
    query[0].value.blob = alias_blob;
    Asset_Attr update[1]{};
    update[0].tag = ASSET_TAG_SECRET;
    update[0].value.blob = secret_blob;
    const int32_t update_result = OH_Asset_Update(query, 1, update, 1);
    if (update_result == ASSET_SUCCESS) return 0;
    if (update_result != ASSET_NOT_FOUND) return translated(update_result);

    Asset_Attr attributes[3]{};
    attributes[0].tag = ASSET_TAG_ACCESSIBILITY;
    attributes[0].value.u32 = ASSET_ACCESSIBILITY_DEVICE_FIRST_UNLOCKED;
    attributes[1].tag = ASSET_TAG_SECRET;
    attributes[1].value.blob = secret_blob;
    attributes[2].tag = ASSET_TAG_ALIAS;
    attributes[2].value.blob = alias_blob;
    return translated(OH_Asset_Add(attributes, sizeof(attributes) / sizeof(attributes[0])));
#else
    return -5;
#endif
}

YCORE_API ysecure_read_result ysecure_get(const char *alias) {
    if (alias == nullptr || alias[0] == '\0') return {-1, nullptr};
#if YFUSE_HAS_ASSET_STORE
    Asset_Blob alias_blob = blob(alias);
    Asset_Attr query[2]{};
    query[0].tag = ASSET_TAG_ALIAS;
    query[0].value.blob = alias_blob;
    query[1].tag = ASSET_TAG_RETURN_TYPE;
    query[1].value.u32 = ASSET_RETURN_ALL;
    Asset_ResultSet result_set{};
    const int32_t query_result = OH_Asset_Query(query, sizeof(query) / sizeof(query[0]), &result_set);
    if (query_result != ASSET_SUCCESS || result_set.count != 1) {
        OH_Asset_FreeResultSet(&result_set);
        return {translated(query_result), nullptr};
    }
    Asset_Attr *secret = OH_Asset_ParseAttr(result_set.results, ASSET_TAG_SECRET);
    if (secret == nullptr || secret->value.blob.data == nullptr || secret->value.blob.size == 0) {
        OH_Asset_FreeResultSet(&result_set);
        return {-5, nullptr};
    }
    auto *copy = static_cast<char *>(std::malloc(static_cast<size_t>(secret->value.blob.size) + 1));
    if (copy == nullptr) {
        OH_Asset_FreeResultSet(&result_set);
        return {-5, nullptr};
    }
    std::memcpy(copy, secret->value.blob.data, secret->value.blob.size);
    copy[secret->value.blob.size] = '\0';
    OH_Asset_FreeResultSet(&result_set);
    return {0, copy};
#else
    return {-5, nullptr};
#endif
}

YCORE_API int ysecure_remove(const char *alias) {
    if (alias == nullptr || alias[0] == '\0') return -1;
#if YFUSE_HAS_ASSET_STORE
    Asset_Blob alias_blob = blob(alias);
    Asset_Attr query[1]{};
    query[0].tag = ASSET_TAG_ALIAS;
    query[0].value.blob = alias_blob;
    return translated(OH_Asset_Remove(query, 1));
#else
    return -5;
#endif
}

YCORE_API void ysecure_free(char *value) { std::free(value); }

YCORE_API char *ysecure_random_reference() {
    try {
        static constexpr char hex[] = "0123456789abcdef";
        std::random_device random;
        auto *value = static_cast<char *>(std::malloc(32 + 1));
        if (value == nullptr) return nullptr;
        for (size_t index = 0; index < 16; ++index) {
            const unsigned byte = random() & 0xffu;
            value[index * 2] = hex[(byte >> 4) & 0x0f];
            value[index * 2 + 1] = hex[byte & 0x0f];
        }
        value[32] = '\0';
        return value;
    } catch (...) {
        return nullptr;
    }
}

}
