/*
 * Copyright (c) 2024 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "oh_preferences.h"

#include "application_context.h"
#include "oh_convertor.h"
#include "log_print.h"
#include "oh_preferences_err_code.h"
#include "oh_preferences_impl.h"
#include "oh_preferences_value_impl.h"
#include "oh_preferences_value.h"
#include "preferences_file_operation.h"
#include "preferences_helper.h"
#include "preferences_observer.h"
#include "securec.h"

using namespace OHOS::PreferencesNdk;
using namespace OHOS::AbilityRuntime;
using RegisterMode = OHOS::NativePreferences::PreferencesObserver::RegisterMode;

OH_PreferencesImpl::OH_PreferencesImpl
    (std::shared_ptr<OHOS::NativePreferences::Preferences> preferences) : preferences_(preferences)
{
}

bool NDKPreferencesUtils::PreferencesStructValidCheck(int64_t originCid, int64_t targetCid)
{
    if (originCid != targetCid) {
        LOG_ERROR("cid check failed, ori cid: %{public}ld, target cid: %{public}ld", static_cast<long>(originCid),
            static_cast<long>(targetCid));
        return false;
    }
    return true;
}

std::pair<int, std::string> GetPreferencesDir(OH_PreferencesOption *options)
{
    auto context = OHOS::AbilityRuntime::Context::GetApplicationContext();
    if (context == nullptr) {
        LOG_ERROR("get application context go wrong");
        return { OH_Preferences_ErrCode::PREFERENCES_ERROR_STORAGE, "" };
    }
    if (options->GetDataGroupId().empty()) {
        return { OH_Preferences_ErrCode::PREFERENCES_OK, context->GetPreferencesDir() };
    }
    std::string stagePreferencesDir;
    int err = context->GetSystemPreferencesDir(options->GetDataGroupId(), false, stagePreferencesDir);
    if (err != 0) {
        LOG_ERROR("get system preferences dir failed, err: %{public}d", err);
        return { OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM, stagePreferencesDir };
    }
    return { OH_Preferences_ErrCode::PREFERENCES_OK, stagePreferencesDir };
}

OH_Preferences *OH_Preferences_Open(OH_PreferencesOption *option, int *errCode)
{
    if (option == nullptr || option->fileName.empty() || !NDKPreferencesUtils::PreferencesStructValidCheck(
        option->cid, PreferencesNdkStructId::PREFERENCES_OH_OPTION_CID) || errCode == nullptr) {
        LOG_ERROR("open preference cfg error, option is null: %{public}d, fileName is null: %{public}d, "
            "errCode is null: %{public}d, err:%{public}d",
            (option == nullptr), (option == nullptr) ? 1 : option->fileName.empty(), (errCode == nullptr),
            OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        if (errCode != nullptr) {
            *errCode = OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
        }
        return nullptr;
    }

    auto dirRes = GetPreferencesDir(option);
    if (dirRes.first != OH_Preferences_ErrCode::PREFERENCES_OK) {
        *errCode = dirRes.first;
        return nullptr;
    }
    std::string filePath = dirRes.second + "/" + option->GetFileName();

    Preferences_StorageType type = option->GetStorageType();
    OHOS::NativePreferences::Options nativeOptions(filePath, option->GetBundleName(),
        option->GetDataGroupId(), type == PREFERENCES_STORAGE_GSKV);

    int nativeErr = OHOS::NativePreferences::E_OK;
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences=
        OHOS::NativePreferences::PreferencesHelper::GetPreferences(nativeOptions, nativeErr);
    *errCode = OHConvertor::NativeErrToNdk(nativeErr);
    if (innerPreferences == nullptr || *errCode != OH_Preferences_ErrCode::PREFERENCES_OK) {
        LOG_ERROR("Get native Preferences failed: %{public}s, errcode: %{public}d",
            OHOS::NativePreferences::ExtractFileName(nativeOptions.filePath).c_str(), *errCode);
        return nullptr;
    }
    OH_PreferencesImpl *preferenceImpl = new (std::nothrow) OH_PreferencesImpl(innerPreferences);
    if (preferenceImpl == nullptr) {
        innerPreferences = nullptr;
        LOG_ERROR("new impl object failed");
        *errCode = OH_Preferences_ErrCode::PREFERENCES_ERROR_MALLOC;
        return nullptr;
    }
    preferenceImpl->SetPreferencesStoreFilePath(filePath);
    preferenceImpl->cid = PreferencesNdkStructId::PREFERENCES_OH_PREFERENCES_CID;
    return static_cast<OH_Preferences *>(preferenceImpl);
}

static OH_PreferencesImpl *GetPreferencesImpl(OH_Preferences *preference)
{
    if (preference == nullptr ||
        !NDKPreferencesUtils::PreferencesStructValidCheck(
            preference->cid, PreferencesNdkStructId::PREFERENCES_OH_PREFERENCES_CID)) {
        LOG_ERROR("preference invalid, is null: %{public}d", preference == nullptr);
        return nullptr;
    }
    return static_cast<OH_PreferencesImpl *>(preference);
}

static std::shared_ptr<OHOS::NativePreferences::Preferences> GetNativePreferencesFromOH(OH_Preferences *preference)
{
    auto preferencesImpl = GetPreferencesImpl(preference);
    if (preferencesImpl == nullptr ||
        !NDKPreferencesUtils::PreferencesStructValidCheck(
            preference->cid, PreferencesNdkStructId::PREFERENCES_OH_PREFERENCES_CID)) {
        LOG_ERROR("preferences is null: %{public}d when get native preferences from ohPreferences",
            (preferencesImpl == nullptr));
        return nullptr;
    }
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = preferencesImpl->GetNativePreferences();
    if (innerPreferences== nullptr) {
        LOG_ERROR("preference not open yet");
        return nullptr;
    }
    return innerPreferences;
}

void OH_PreferencesImpl::SetPreferencesStoreFilePath(const std::string &filePath)
{
    std::unique_lock<std::shared_mutex> writeLock(mutex_);
    filePath_ = filePath;
}

std::string OH_PreferencesImpl::GetPreferencesStoreFilePath()
{
    std::shared_lock<std::shared_mutex> readLock(mutex_);
    return filePath_;
}

int OH_Preferences_Close(OH_Preferences *preference)
{
    auto preferencesImpl = GetPreferencesImpl(preference);
    if (preferencesImpl == nullptr) {
        LOG_ERROR("preferences close failed, preferences is null");
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = preferencesImpl->GetNativePreferences();
    if (innerPreferences== nullptr) {
        LOG_ERROR("preference not open yet");
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    innerPreferences->FlushSync();

    int errCode = OHOS::NativePreferences::PreferencesHelper::RemovePreferencesFromCache(
        preferencesImpl->GetPreferencesStoreFilePath());
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("preference close failed: %{public}d", errCode);
        return OHConvertor::NativeErrToNdk(errCode);
    }
    delete preferencesImpl;
    return OH_Preferences_ErrCode::PREFERENCES_OK;
}

int OH_Preferences_DeletePreferences(OH_PreferencesOption *option)
{
    if (option == nullptr || option->fileName.empty() || !NDKPreferencesUtils::PreferencesStructValidCheck(
        option->cid, PreferencesNdkStructId::PREFERENCES_OH_OPTION_CID)) {
        LOG_ERROR("delete preference error, option is null: %{public}d, fileName is null: %{public}d, ",
            (option == nullptr), (option == nullptr) ? 1 : option->fileName.empty());
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    auto dirRes = GetPreferencesDir(option);
    if (dirRes.first != OH_Preferences_ErrCode::PREFERENCES_OK || dirRes.second.empty()) {
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    int errCode = OHOS::NativePreferences::PreferencesHelper::DeletePreferences(
        dirRes.second.append("/").append(option->fileName));
    return OHConvertor::NativeErrToNdk(errCode);
}

int OH_Preferences_SetValue(OH_Preferences *preference, const char *key, OH_PreferencesValue *value)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr || value == nullptr) {
        LOG_ERROR("set value failed, preference not open yet: %{public}d, key is null: %{public}d, "
            "value is null: %{public}d, err: %{public}d", (innerPreferences== nullptr), (key == nullptr),
            (value == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    std::string keyStr(key);
    int errCode = innerPreferences->Put(keyStr, static_cast<OH_PreferencesValueImpl*>(value)->value_);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("put value impl failed");
        return PREFERENCES_ERROR_STORAGE;
    }
    return PREFERENCES_OK;
}

int OH_Preferences_GetValue(OH_Preferences *preference, const char *key, OH_PreferencesValue **value)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr || value == nullptr) {
        LOG_ERROR("get value failed, preference not open yet: %{public}d, key is null: %{public}d, "
            "value is null: %{public}d, err: %{public}d", (innerPreferences== nullptr), (key == nullptr),
            (value == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    std::string keyStr(key);
    OHOS::NativePreferences::PreferencesValue prefValue = innerPreferences->Get(keyStr,
        OHOS::NativePreferences::PreferencesValue());
    if (prefValue == nullptr) {
        LOG_ERROR("get value impl failed");
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }
    static_cast<OH_PreferencesValueImpl*>(*value)->value_ = prefValue;
    return PREFERENCES_OK;
}

uint32_t OH_PreferencesImpl::PackData(OH_PreferencesPair **pairs, std::unordered_map<std::string,
    OHOS::NativePreferences::PreferencesValue> &res)
{
    uint32_t i = 0;
    for (auto &[key, value] : res) {
        (*pairs)[i].cid = PreferencesNdkStructId::PREFERENCES_OH_PAIR_CID;
        (*pairs)[i].maxIndex = res.size();
        (*pairs)[i].key = strdup(key.c_str());
        if ((*pairs)[i].key == nullptr) {
            LOG_ERROR("malloc key failed");
            return i;
        }
        OH_PreferencesValueImpl* valueImpl = new (std::nothrow) OH_PreferencesValueImpl();
        if (valueImpl == nullptr) {
            LOG_ERROR("malloc value impl failed");
            return i;
        }
        valueImpl->cid = PreferencesNdkStructId::PREFERENCES_OH_VALUE_CID;
        valueImpl->value_ = value;
        (*pairs)[i].value = reinterpret_cast<OH_PreferencesValue*>(valueImpl);
        i++;
    }
    return 0;
}

int OH_Preferences_GetAll(OH_Preferences *preference, OH_PreferencesPair **pairs, uint32_t *count)
{
    auto preferencesImpl = GetPreferencesImpl(preference);
    if (preferencesImpl == nullptr || pairs == nullptr || count == nullptr ||
        !NDKPreferencesUtils::PreferencesStructValidCheck(
            preference->cid, PreferencesNdkStructId::PREFERENCES_OH_PREFERENCES_CID)) {
        LOG_ERROR("preferences is null: preference not open yet: %{public}d, pairs is null: %{public}d, "
            "count is null: %{public}d, err: %{public}d", (preferencesImpl == nullptr), (pairs == nullptr),
            (count == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = preferencesImpl->GetNativePreferences();
    if (innerPreferences== nullptr) {
        LOG_ERROR("preference not open yet");
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    auto res = innerPreferences->GetAllDatas();
    if (res.empty()) {
        LOG_ERROR("get all data failed");
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_KEY_NOT_FOUND;
    }

    *count = res.size();
    *pairs = (OH_PreferencesPair *)malloc(res.size() * sizeof(OH_PreferencesPair));
    if (*pairs == nullptr) {
        LOG_ERROR("malloc pairs failed");
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_MALLOC;
    }

    uint32_t iCount = preferencesImpl->PackData(pairs, res);
    if (iCount != 0) {
        OH_PreferencesPair_Destroy(*pairs, iCount);
        *pairs = nullptr;
        LOG_ERROR("malloc pairs failed");
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_MALLOC;
    }

    return OH_Preferences_ErrCode::PREFERENCES_OK;
}

bool OH_Preferences_HasKey(OH_Preferences *preference, const char *key)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr) {
        LOG_ERROR("has key failed, preference not open yet: %{public}d, key is null: %{public}d, "
            "err: %{public}d", (innerPreferences== nullptr), (key == nullptr),
            OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return false;
    }
    std::string keyStr(key);
    return innerPreferences->HasKey(keyStr);
}

int OH_Preferences_Flush(OH_Preferences *preference)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences == nullptr) {
        LOG_ERROR("flush failed, preference not open yet: %{public}d, "
            "err: %{public}d", (innerPreferences== nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    int errCode = innerPreferences->FlushSync();
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("preference flush failed: %{public}d", errCode);
        return OHConvertor::NativeErrToNdk(errCode);
    }

    return OH_Preferences_ErrCode::PREFERENCES_OK;
}

int OH_Preferences_ClearCache(OH_Preferences *preference)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences == nullptr) {
        LOG_ERROR("clear cache failed, preference not open yet: %{public}d, "
            "err: %{public}d", (innerPreferences== nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    int errCode = innerPreferences->Clear();
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("preference clear cache failed: %{public}d", errCode);
        return OHConvertor::NativeErrToNdk(errCode);
    }

    return OH_Preferences_ErrCode::PREFERENCES_OK;
}

int OH_Preferences_GetInt(OH_Preferences *preference, const char *key, int *value)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr || value == nullptr) {
        LOG_ERROR("get int failed, preference not open yet: %{public}d, key is null: %{public}d, "
            "value is null: %{public}d, err: %{public}d", (innerPreferences== nullptr), (key == nullptr),
            (value == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    auto res = innerPreferences->GetValue(key, OHOS::NativePreferences::PreferencesValue());
    if (res.first != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("Get Int failed, %{public}d", res.first);
        return OHConvertor::NativeErrToNdk(res.first);
    }

    if (res.second.IsInt()) {
        *value = (int)(res.second);
    } else {
        LOG_ERROR("Get Int failed, value's type is not int");
        if (res.first == OHOS::NativePreferences::E_OK) {
            return OH_Preferences_ErrCode::PREFERENCES_ERROR_KEY_NOT_FOUND;
        }
    }

    return OHConvertor::NativeErrToNdk(res.first);
}

int OH_Preferences_GetString(OH_Preferences *preference, const char *key, char **value,
    uint32_t *valueLen)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr || value == nullptr || valueLen == nullptr) {
        LOG_ERROR("get str failed, preference not open yet: %{public}d, key is null: %{public}d, "
            "value is null: %{public}d, valueLen is null: %{public}d, err: %{public}d",
            (innerPreferences== nullptr), (key == nullptr), (value == nullptr), (valueLen == nullptr),
            OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    auto res = innerPreferences->GetValue(key, OHOS::NativePreferences::PreferencesValue());
    if (res.first != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("Get string failed, %{public}d", res.first);
        return OHConvertor::NativeErrToNdk(res.first);
    }

    if (res.second.IsString()) {
        std::string str = (std::string)(res.second);
        size_t strLen = str.size();
        if (strLen >= SIZE_MAX) {
            LOG_ERROR(" string length overlimit: %{public}zu", strLen);
            return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
        }
        void *ptr = malloc(strLen + 1);
        if (ptr == nullptr) {
            LOG_ERROR("malloc failed when get string, errno: %{public}d", errno);
            return OH_Preferences_ErrCode::PREFERENCES_ERROR_MALLOC;
        }
        int sysErr = memset_s(ptr, (strLen + 1), 0, (strLen + 1));
        if (sysErr != EOK) {
            LOG_ERROR("memset failed when get string, errCode: %{public}d", sysErr);
        }
        if (strLen > 0) {
            sysErr = memcpy_s(ptr, strLen, str.c_str(), strLen);
            if (sysErr != EOK) {
                LOG_ERROR("memcpy failed when get string, errCode: %{public}d", sysErr);
                free(ptr);
                return OH_Preferences_ErrCode::PREFERENCES_ERROR_MALLOC;
            }
        }
        *value = reinterpret_cast<char *>(ptr);
        *valueLen = strLen + 1;
    } else {
        LOG_ERROR("Get string failed, value's type is not string, err: %{public}d", res.first);
        if (res.first == OHOS::NativePreferences::E_OK) {
            return OH_Preferences_ErrCode::PREFERENCES_ERROR_KEY_NOT_FOUND;
        }
    }

    return OHConvertor::NativeErrToNdk(res.first);
}

void OH_Preferences_FreeString(char *string)
{
    free(string);
}

int OH_Preferences_GetBool(OH_Preferences *preference, const char *key, bool *value)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr || value == nullptr) {
        LOG_ERROR("get bool failed, preference not open yet: %{public}d, key is null: %{public}d, "
            "value is null: %{public}d, err: %{public}d", (innerPreferences== nullptr), (key == nullptr),
            (value == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    auto res = innerPreferences->GetValue(key, OHOS::NativePreferences::PreferencesValue());
    if (res.first != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("Get bool failed, %{public}d", res.first);
        return OHConvertor::NativeErrToNdk(res.first);
    }

    if (res.second.IsBool()) {
        *value = (bool)(res.second);
    } else {
        LOG_ERROR("Get bool failed, value's type is not bool, err: %{public}d", res.first);
        if (res.first == OHOS::NativePreferences::E_OK) {
            return OH_Preferences_ErrCode::PREFERENCES_ERROR_KEY_NOT_FOUND;
        }
    }

    return OHConvertor::NativeErrToNdk(res.first);
}

int OH_Preferences_SetInt(OH_Preferences *preference, const char *key, int value)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr) {
        LOG_ERROR("set int failed, preference not open yet: %{public}d, key is null: %{public}d, err: %{public}d",
            (innerPreferences== nullptr), (key == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    int errCode = innerPreferences->PutInt(key, value);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("preference put int failed, err: %{public}d", errCode);
    }
    return OHConvertor::NativeErrToNdk(errCode);
}

int OH_Preferences_SetBool(OH_Preferences *preference, const char *key, bool value)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr) {
        LOG_ERROR("set bool failed, preference not open yet: %{public}d, key is null: %{public}d, err: %{public}d",
            (innerPreferences== nullptr), (key == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    int errCode = innerPreferences->PutBool(key, value);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("preference put bool failed, err: %{public}d", errCode);
    }
    return OHConvertor::NativeErrToNdk(errCode);
}

int OH_Preferences_SetString(OH_Preferences *preference, const char *key, const char *value)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr || value == nullptr) {
        LOG_ERROR("set str failed, preference not open yet: %{public}d, key is null: %{public}d, "
            "value is null: %{public}d, err: %{public}d", (innerPreferences== nullptr), (key == nullptr),
            (value == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    int errCode = innerPreferences->PutString(key, value);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("preference put string failed, err: %{public}d", errCode);
    }
    return OHConvertor::NativeErrToNdk(errCode);
}

int OH_Preferences_Delete(OH_Preferences *preference, const char *key)
{
    std::shared_ptr<OHOS::NativePreferences::Preferences> innerPreferences = GetNativePreferencesFromOH(preference);
    if (innerPreferences== nullptr || key == nullptr) {
        LOG_ERROR("delete failed, preference not open yet: %{public}d, key is null: %{public}d, err: %{public}d",
            (innerPreferences== nullptr), (key == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    int errCode = innerPreferences->Delete(key);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("preference delete value failed, err: %{public}d", errCode);
    }
    return OHConvertor::NativeErrToNdk(errCode);
}

int OH_Preferences_RegisterDataObserver(OH_Preferences *preference, void *context,
    OH_PreferencesDataObserver observer, const char *keys[], uint32_t keyCount)
{
    auto preferencesImpl = GetPreferencesImpl(preference);
    if (preferencesImpl == nullptr || observer == nullptr || keys == nullptr) {
        LOG_ERROR("register failed, sp is null ? %{public}d, obs is null ? %{public}d, "
            "keys is null: %{public}d, err: %{public}d", (preferencesImpl == nullptr), (observer == nullptr),
            (keys == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    std::vector<std::string> keysVec;
    for (uint32_t i = 0; i < keyCount; i++) {
        keysVec.push_back(keys[i]);
    }
 
    return OHConvertor::NativeErrToNdk(preferencesImpl->RegisterDataObserver(observer, context, keysVec));
}

int OH_Preferences_RegisterMultiProcessDataObserver(OH_Preferences *preference, void *context,
    OH_PreferencesDataObserver observer)
{
    auto preferencesImpl = GetPreferencesImpl(preference);
    if (preferencesImpl == nullptr || observer == nullptr) {
        LOG_ERROR("register failed, sp is null ? %{public}d, obs is null ? %{public}d, err: %{public}d",
            (preferencesImpl == nullptr), (observer == nullptr),
            OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    return OHConvertor::NativeErrToNdk(preferencesImpl->RegisterMultiProcessDataObserver(observer, context));
}

int OH_Preferences_UnregisterDataObserver(OH_Preferences *preference, void *context,
    OH_PreferencesDataObserver observer, const char *keys[], uint32_t keyCount)
{
    auto preferencesImpl = GetPreferencesImpl(preference);
    if (preferencesImpl == nullptr || observer == nullptr || keys == nullptr) {
        LOG_ERROR("unregister failed, sp is null ? %{public}d, obs is null ? %{public}d, "
            "keys is null: %{public}d, err: %{public}d", (preferencesImpl == nullptr), (observer == nullptr),
            (keys == nullptr), OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }
    std::vector<std::string> keysVec;
    for (uint32_t i = 0; i < keyCount; i++) {
        keysVec.push_back(keys[i]);
    }
    return OHConvertor::NativeErrToNdk(preferencesImpl->UnregisterDataObserver(observer, context, keysVec));
}

int OH_Preferences_UnregisterMultiProcessDataObserver(OH_Preferences *preference, void *context,
    OH_PreferencesDataObserver observer)
{
    auto preferencesImpl = GetPreferencesImpl(preference);
    if (preferencesImpl == nullptr || observer == nullptr) {
        LOG_ERROR("unregister failed, sp is null ? %{public}d, obs is null ? %{public}d, err: %{public}d",
            (preferencesImpl == nullptr), (observer == nullptr),
            OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }
    return OHConvertor::NativeErrToNdk(preferencesImpl->UnregisterMultiProcessDataObserver(observer, context));
}

int OH_Preferences_IsStorageTypeSupported(Preferences_StorageType type, bool *isSupported)
{
    if (type < Preferences_StorageType::PREFERENCES_STORAGE_XML ||
        type > Preferences_StorageType::PREFERENCES_STORAGE_GSKV || isSupported == nullptr) {
        LOG_ERROR("param check failed, type: %{public}d, isSupported is null: %{public}d", static_cast<int>(type),
            isSupported == nullptr);
        return OH_Preferences_ErrCode::PREFERENCES_ERROR_INVALID_PARAM;
    }

    *isSupported = OHOS::NativePreferences::PreferencesHelper::
        IsStorageTypeSupported(OHConvertor::NdkStorageTypeToNative(type));
    if (!*isSupported) {
        LOG_WARN("current type not supported on this platform");
    }
    return OH_Preferences_ErrCode::PREFERENCES_OK;
}

int OH_PreferencesImpl::RegisterDataObserver(
    OH_PreferencesDataObserver observer, void *context, const std::vector<std::string> &keys)
{
    std::unique_lock<std::shared_mutex> writeLock(obsMutex_);

    auto ndkObserver = std::make_shared<NDKPreferencesObserver>(observer, context);
    int errCode = preferences_->RegisterDataObserver(ndkObserver, keys);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("register failed, err: %{public}d", errCode);
    } else {
        dataObservers_.emplace_back(std::make_pair(std::move(ndkObserver), context));
    }
    return OHConvertor::NativeErrToNdk(errCode);
}

int OH_PreferencesImpl::RegisterMultiProcessDataObserver(OH_PreferencesDataObserver observer, void *context)
{
    std::unique_lock<std::shared_mutex> writeLock(obsMutex_);

    auto ndkObserver = std::make_shared<NDKPreferencesObserver>(observer, context);
    int errCode = preferences_->RegisterObserver(ndkObserver, RegisterMode::MULTI_PRECESS_CHANGE);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("register failed, err: %{public}d", errCode);
    }
    return OHConvertor::NativeErrToNdk(errCode);
}

NDKPreferencesObserver::NDKPreferencesObserver(OH_PreferencesDataObserver observer, void *context)
    : dataObserver_(observer), context_(context) {}

inline void FreePairValue(OH_PreferencesPair *pairs, size_t count)
{
    for (size_t i = 0; i < count; i++) {
        delete pairs[i].value;
    }
}

void NDKPreferencesObserver::OnChange(const std::map<std::string, OHOS::NativePreferences::PreferencesValue> &records)
{
    if (dataObserver_ == nullptr) {
        LOG_ERROR("failed to trigger change, data observer is null");
        return;
    }
    auto count = records.size();
    if (count == 0) {
        return;
    }
    
    std::vector<std::string> keys;
    keys.reserve(count);
    for (const auto &[key, value] : records) {
        keys.push_back(key);
    }
    
    OH_PreferencesPair *pairs = new (std::nothrow) OH_PreferencesPair[count];
    if (pairs == nullptr) {
        LOG_ERROR("malloc pairs failed when on change, count: %{public}d, errno:%{public}d", static_cast<int>(count),
            errno);
        return;
    }
    int i = 0;
    for (const auto &[key, value] : records) {
        OH_PreferencesValueImpl *valueImpl = new (std::nothrow) OH_PreferencesValueImpl();
        if (valueImpl == nullptr) {
            LOG_ERROR("new value object failed");
            FreePairValue(pairs, i);
            delete []pairs;
            return;
        }
        valueImpl->cid = PreferencesNdkStructId::PREFERENCES_OH_VALUE_CID;
        valueImpl->value_ = value;
        pairs[i] = OH_PreferencesPair { PreferencesNdkStructId::PREFERENCES_OH_PAIR_CID, keys[i].c_str(),
            static_cast<OH_PreferencesValue *>(valueImpl), count};
        i++;
    }
    (dataObserver_)(context_, pairs, count);
    FreePairValue(pairs, count);
    delete []pairs;
}

void NDKPreferencesObserver::OnChange(const std::string &key)
{
}

int OH_PreferencesImpl::UnregisterDataObserver(OH_PreferencesDataObserver observer, void *context,
    const std::vector<std::string> &keys)
{
    std::unique_lock<std::shared_mutex> writeLock(obsMutex_);
    if (dataObservers_.empty()) {
        return OH_Preferences_ErrCode::PREFERENCES_OK;
    }
    for (int i = static_cast<int>(dataObservers_.size()) - 1; i >= 0; i--) {
        if (!dataObservers_[i].first->ObserverCompare(observer) || dataObservers_[i].second != context) {
            continue;
        }

        int errCode = preferences_->UnRegisterDataObserver(dataObservers_[i].first, keys);
        if (errCode != OHOS::NativePreferences::E_OK) {
            LOG_ERROR("un register observer failed, err: %{public}d", errCode);
            return OHConvertor::NativeErrToNdk(errCode);
        }
        if (keys.empty()) {
            dataObservers_[i] = { nullptr, nullptr };
            dataObservers_.erase(dataObservers_.begin() + i);
        }
    }
    return OH_Preferences_ErrCode::PREFERENCES_OK;
}

int OH_PreferencesImpl::UnregisterMultiProcessDataObserver(OH_PreferencesDataObserver observer, void *context)
{
    auto ndkObserver = std::make_shared<NDKPreferencesObserver>(observer, context);
    int errCode = preferences_->UnRegisterObserver(ndkObserver, RegisterMode::MULTI_PRECESS_CHANGE);
    if (errCode != OHOS::NativePreferences::E_OK) {
        LOG_ERROR("un register observer failed, err: %{public}d", errCode);
        return OHConvertor::NativeErrToNdk(errCode);
    }
    return OH_Preferences_ErrCode::PREFERENCES_OK;
}

bool NDKPreferencesObserver::ObserverCompare(OH_PreferencesDataObserver other)
{
    if (other == nullptr) {
        return false;
    }
    return  other == dataObserver_;
}
