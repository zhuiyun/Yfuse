#include "ycore/ycore.h"
#include <database/preferences/oh_preferences.h>
#include <database/preferences/oh_preferences_option.h>
#include <database/preferences/oh_preferences_err_code.h>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

namespace {
std::mutex documentMutex;
OH_Preferences *openDocuments(int &error) {
    auto *option = OH_PreferencesOption_Create();
    if (!option) { error = -1; return nullptr; }
    error = OH_PreferencesOption_SetFileName(option, "yfuse_state_v1");
    auto *result = error == 0 ? OH_Preferences_Open(option, &error) : nullptr;
    OH_PreferencesOption_Destroy(option);
    return result;
}
bool validKey(const char *key) {
    return key && (std::strcmp(key, "server_registry_v1") == 0 || std::strcmp(key, "playback_progress_v1") == 0);
}
}
extern "C" {
struct ypreferences_read_result { int status; char *value; };
YCORE_API ypreferences_read_result ypreferences_get(const char *key) {
    if (!validKey(key)) return {-1, nullptr};
    std::lock_guard<std::mutex> guard(documentMutex);
    int status = 0;
    auto *store = openDocuments(status);
    if (!store) return {status == 0 ? -1 : status, nullptr};
    char *value = nullptr;
    uint32_t length = 0;
    status = OH_Preferences_GetString(store, key, &value, &length);
    char *copy = status == 0 && value ? ::strdup(value) : nullptr;
    if (value) OH_Preferences_FreeString(value);
    const int closed = OH_Preferences_Close(store);
    if (closed != 0) { std::free(copy); return {closed, nullptr}; }
    if (status == PREFERENCES_ERROR_KEY_NOT_FOUND) return {-2, nullptr};
    if (status == 0 && !copy) return {-1, nullptr};
    return {status, copy};
}
YCORE_API int ypreferences_put(const char *key, const char *value) {
    if (!validKey(key) || !value || std::strlen(value) > 1024 * 1024) return -1;
    std::lock_guard<std::mutex> guard(documentMutex);
    int status = 0;
    auto *store = openDocuments(status);
    if (!store) return status == 0 ? -1 : status;
    status = OH_Preferences_SetString(store, key, value);
    const int closed = OH_Preferences_Close(store);
    if (status != 0 || closed != 0) return status != 0 ? status : closed;
    // API 20 has no public Flush result. Close flushes and evicts the cache; reopen and
    // compare the durable document before acknowledging the write to the registry.
    store = openDocuments(status);
    if (!store) return status == 0 ? -1 : status;
    char *verified = nullptr;
    uint32_t length = 0;
    status = OH_Preferences_GetString(store, key, &verified, &length);
    const bool equal = status == 0 && verified && std::strcmp(value, verified) == 0;
    if (verified) OH_Preferences_FreeString(verified);
    const int verifyClosed = OH_Preferences_Close(store);
    return equal && verifyClosed == 0 ? 0 : -1;
}
YCORE_API void ypreferences_free(char *value) { std::free(value); }
}
