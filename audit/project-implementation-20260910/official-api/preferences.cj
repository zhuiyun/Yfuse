/*
 * Copyright (c) 2025 Huawei Device Co., Ltd.
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

// The Cangjie API is in Beta. For details on its capabilities and limitations, please refer to the README file.

package ohos.data.preferences

import ohos.ffi.{RemoteDataLite, releaseFFIData, SUCCESS_CODE, Callback1Param}
import ohos.hilog.HilogChannel
import ohos.app.ability.ui_ability.UIAbilityContext
import std.sync.Mutex
import std.collection.{ArrayList, HashMap}
import ohos.labels.APILevel
import ohos.callback_invoke.{ Callback1Argument, CallbackObject }
import ohos.business_exception.{ BusinessException, ERR_PARAMETER_ERROR }

let PREFERENCES_LOG = HilogChannel(0, 0xD002B25, "CJ-Preferences")

type StageContext = CPointer<Unit>

/**
 * Preferences change type.
 */
@!APILevel[
    since: "22",
    syscap: "SystemCapability.DistributedDataManager.Preferences.Core"
]
public enum PreferencesEvent {
    /**
     * PreferencesEvent change enum instance
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core"
    ]
    PreferencesChange
    |
    /**
     * PreferencesEvent multiProcessChange enum instance
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core"
    ]
    PreferencesMultiProcessChange
    | ...

    func getValue(): String {
        match (this) {
            case PreferencesChange => "change"
            case PreferencesMultiProcessChange => "multiProcessChange"
            case _ => throw BusinessException(getCJErrorCode(E_INNER_ERROR), getCJErrorMsg(E_INNER_ERROR))
        }
    }
}

/**
 * Provides interfaces to obtain and modify preferences data.
 * The preferences data is stored in a file, which matches only one Preferences instance in the memory.
 * You can use getPreferences to obtain the Preferences instance matching
 * the file that stores preferences data, and use movePreferencesFromCache
 * to remove the Preferences instance from the memory.
 */
@!APILevel[
    since: "22",
    syscap: "SystemCapability.DistributedDataManager.Preferences.Core"
]
public class Preferences <: RemoteDataLite {
    let callbackList = ArrayList<(CallbackObject, Int64)>()
    let onOffMutex = Mutex()
    init(ctxId: Int64, name: String, dataGroupId: String) {
        super(
            unsafe {
                var codeId = 0i64
                try (
                    cName = LibC.mallocCString(name).asResource(),
                    cDataGroupId = LibC.mallocCString(dataGroupId).asResource()
                ) {
                    var errCode = 0i32
                    codeId = FfiOHOSPreferencesGetPreferencesV1(ctxId, cName.value, cDataGroupId.value, inout errCode)
                    if (errCode != SUCCESS_CODE) {
                        checkCodeAndThrow(errCode, "getPreferences")
                        PREFERENCES_LOG.error(
                            "Preferences getPreferences failed: ${getCJErrorMsg(errCode)}${getNativeErrorMsg(errCode)} code: ${getCJErrorCode(errCode)} nativeErrorCode: ${errCode}"
                        )
                        throw BusinessException(getCJErrorCode(errCode),"Preferences getPreferences failed: ${getCJErrorMsg(errCode)}")
                    }
                }
                codeId
            })
    }

    ~init() {
        releaseFFIData(myDataId)
    }

    /**
     * Obtains a Preferences instance matching a specified preferences file name.
     * The Preferences instance loads all data of the preferences file and
     * resides in the memory. You can use removePreferencesFromCache to remove the instance from the memory.
     *
     * @param { UIAbilityContext } context - Indicates the context of application or capability.
     * @param { String } name - Indicates the preferences file name.
     * @returns { Preferences } The Preferences instance matching the specified preferences file name.
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public static func getPreferences(context: UIAbilityContext, name: String): Preferences {
        return Preferences(context.getID(), name, String.empty)
    }

    /**
     * Obtains a Preferences instance matching a specified preferences file name.
     * The Preferences instance loads all data of the preferences file and
     * resides in the memory. You can use removePreferencesFromCache to remove the instance from the memory.
     *
     * @param { UIAbilityContext } context - Indicates the context of application or capability.
     * @param { PreferencesOptions } options - Indicates the PreferencesOptions option of preferences
     * file position.
     * @returns { Preferences } The Preferences instance matching the specified preferences file name.
     * @throws { BusinessException } 801 - Capability not supported.
     * @throws { BusinessException } 15500000 - Inner error.
     * @throws { BusinessException } 15501001 - The operations is supported in stage mode only.
     * @throws { BusinessException } 15501002 - Invalid dataGroupId.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public static func getPreferences(context: UIAbilityContext, options: PreferencesOptions): Preferences {
        return Preferences(context.getID(), options.name, options.dataGroupId)
    }

    /**
     * Deletes a Preferences instance matching a specified preferences file name
     * from the cache which is performed by removePreferencesFromCache and deletes the
     * preferences file.
     * When deleting the Preferences instance, you must release all references
     * of the instance. In addition, do not use the instance to perform data operations. Otherwise, data inconsistency
     * will occur.
     *
     * @param { UIAbilityContext } context - Indicates the context of application or capability.
     * @param { String } name - Indicates the preferences file name.
     * @throws { BusinessException } 15500000 - Inner error.
     * @throws { BusinessException } 15500010 - Failed to delete the user preferences persistence file.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public static func deletePreferences(context: UIAbilityContext, name: String): Unit {
        deletePreferences(context, PreferencesOptions(name))
    }

    /**
     * Deletes a Preferences instance matching a specified preferences file name
     * from the cache which is performed by removePreferencesFromCache and deletes the
     * preferences file.
     * When deleting the Preferences instance, you must release all references
     * of the instance. In addition, do not use the instance to perform data operations. Otherwise, data inconsistency
     * will occur.
     *
     * @param { UIAbilityContext } context - Indicates the context of application or capability.
     * @param { PreferencesOptions } options - Indicates the PreferencesOptions option of preferences
     * file position.
     * @throws { BusinessException } 801 - Capability not supported.
     * @throws { BusinessException } 15500000 - Inner error.
     * @throws { BusinessException } 15500010 - Failed to delete the user preferences persistence file.
     * @throws { BusinessException } 15501001 - The operations is supported in stage mode only.
     * @throws { BusinessException } 15501002 - Invalid dataGroupId.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public static func deletePreferences(context: UIAbilityContext, options: PreferencesOptions): Unit {
        var errCode: Int32 = 0
        unsafe {
            try (
                cName = LibC.mallocCString(options.name).asResource(),
                cDataGroupId = LibC.mallocCString(options.dataGroupId).asResource()
            ) {
                errCode = FfiOHOSPreferencesDeletePreferencesV1(context.getID(), cName.value, cDataGroupId.value)
            }
        }
        if (errCode != SUCCESS_CODE) {
            checkCodeAndThrow(errCode, "deletePreferences")
            PREFERENCES_LOG.error(
                "Preferences deletePreferences failed: ${getCJErrorMsg(errCode)}${getNativeErrorMsg(errCode)} code: ${getCJErrorCode(errCode)} nativeErrorCode: ${errCode}"
            )
            throw BusinessException(getCJErrorCode(errCode),
                "Preferences deletePreferences failed: ${getCJErrorMsg(errCode)}")
        }
    }

    /**
     * Deletes a Preferences instance matching a specified preferences file name
     * from the cache.
     * When deleting the Preferences instance, you must release all references
     * of the instance. In addition, do not use the instance to perform data operations. Otherwise, data inconsistency
     * will occur.
     *
     * @param { UIAbilityContext } context - Indicates the context of application or capability.
     * @param { String } name - Indicates the preferences file name.
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public static func removePreferencesFromCache(context: UIAbilityContext, name: String): Unit {
        removePreferencesFromCache(context, PreferencesOptions(name))
    }

    /**
     * Deletes a Preferences instance matching a specified preferences file name
     * from the cache.
     * When deleting the Preferences instance, you must release all references
     * of the instance. In addition, do not use the instance to perform data operations. Otherwise, data inconsistency
     * will occur.
     *
     * @param { UIAbilityContext } context - Indicates the context of application or capability.
     * @param { PreferencesOptions } options - Indicates the PreferencesOptions option of preferences file position.
     * @throws { BusinessException } 801 - Capability not supported.
     * @throws { BusinessException } 15500000 - Inner error.
     * @throws { BusinessException } 15501001 - The operations is supported in stage mode only.
     * @throws { BusinessException } 15501002 - Invalid dataGroupId.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public static func removePreferencesFromCache(context: UIAbilityContext, options: PreferencesOptions): Unit {
        var errCode: Int32 = 0
        unsafe {
            try (
                cName = LibC.mallocCString(options.name).asResource(),
                cDataGroupId = LibC.mallocCString(options.dataGroupId).asResource()
            ) {
                errCode = FfiOHOSPreferencesRemovePreferencesFromCacheV1(context.getID(), cName.value, cDataGroupId.value)
            }
        }
        if (errCode != SUCCESS_CODE) {
            checkCodeAndThrow(errCode, "removePreferencesFromCache")
            PREFERENCES_LOG.error(
                "Preferences removePreferencesFromCache failed: ${getCJErrorMsg(errCode)}${getNativeErrorMsg(errCode)} code: ${getCJErrorCode(errCode)} nativeErrorCode: ${errCode}"
            )
            throw BusinessException(getCJErrorCode(errCode),
                "Preferences removePreferencesFromCache failed: ${getCJErrorMsg(errCode)}")
        }
    }

    /**
     * saves the Preferences object to the file.
     *
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public func flush(): Unit {
        unsafe { FfiOHOSPreferencesFlush(getID()) }
    }

    /**
     * Clears all preferences from the Preferences object.
     * You can call the flush method to save the Preferences object to the file.
     *
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public func clear(): Unit {
        unsafe { FfiOHOSPreferencesClear(getID()) }
    }

    /**
     * Obtains the value of a preferences in the PreferencesValueType format.
     * If the value is null or not in the PreferencesValueType format, the default value is returned.
     *
     * @param { String } key - Indicates the key of the preferences. It cannot be null or empty.
     * @param { PreferencesValueType } defValue - Indicates the default value to return.
     * @returns { PreferencesValueType } The value matching the specified key if it is found;
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public func get(key: String, defValue: PreferencesValueType): PreferencesValueType {
        unsafe {
            var result = PreferencesValueType.Integer(0)
            try (cKey = LibC.mallocCString(key).asResource()) {
                let cDefValue = CPreferencesValueType(defValue)
                var cPreferencesValueType = FfiOHOSPreferencesGet(getID(), cKey.value, cDefValue)
                cDefValue.free()
                result = CPreferencesValueType.parse(cPreferencesValueType)
                FfiOHOSPreferencesFreeValueType(inout cPreferencesValueType)
            }
            return result
        }
    }

    /**
     * Sets an int value for the key in the Preferences object.
     * You can call the flush method to save the Preferences object to the
     * file.
     * When the value contains strings in a non-UTF-8 format, use the Uint8Array type for storage.
     * Otherwise, the format of the persistent file is incorrect and the file is damaged.
     * If the corresponding key already exists, the put method will overwrite its value.
     * You can use the hasSync method to check whether the corresponding key-value pair exists.
     *
     * @param { String } key - Indicates the key of the preferences to modify. It cannot be null or empty.
     * @param { PreferencesValueType } value - Indicates the value of the preferences.
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public func put(key: String, value: PreferencesValueType): Unit {
        unsafe {
            try (cKey = LibC.mallocCString(key).asResource()) {
                let cValue = CPreferencesValueType(value)
                let errCode = FfiOHOSPreferencesPut(getID(), cKey.value, cValue)
                cValue.free()
                throwIfNotSuccess(errCode)
            }
        }
    }

    /**
     * Obtains all the keys and values of a preferences in an HashMap.
     *
     * @returns { HashMap<String, PreferencesValueType> } The values and keys in an HashMap.
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public func getAll(): HashMap<String, PreferencesValueType> {
        unsafe {
            var cValues = FfiOHOSPreferencesGetAll(getID())
            let result = CPreferencesValueTypes.parse(cValues)
            FfiOHOSPreferencesFreeValueTypes(inout cValues)
            return result
        }
    }

    /**
     * Deletes the preferences with a specified key from the Preferences object.
     * You can call the flush method to save the Preferences object to the file.
     *
     * @param { String } key - Indicates the key of the preferences to delete. It cannot be null or empty.
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public func delete(key: String): Unit {
        unsafe {
            try (cKey = LibC.mallocCString(key).asResource()) {
                let errCode = FfiOHOSPreferencesDelete(getID(), cKey.value)
                throwIfNotSuccess(errCode)
            }
        }
    }

    /**
     * Checks whether the Preferences object contains a preferences matching a specified key.
     *
     * @param { String } key - Indicates the key of the preferences to modify. It cannot be null or empty.
     * @returns { Bool } true if the Preferences object contains
     * a preferences with the specified key; returns false otherwise.
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true,
        workerthread: true
    ]
    public func has(key: String): Bool {
        unsafe {
            var result = true
            try (cKey = LibC.mallocCString(key).asResource()) {
                result = FfiOHOSPreferencesHas(getID(), cKey.value)
            }
            return result
        }
    }

    private func checkType(tp: String): Unit {
        if (tp != "change" && tp != "multiProcessChange") {
            throw BusinessException(ERR_PARAMETER_ERROR, MESSAGE_TPYE_ERROR)
        }
    }

    /**
     * Registers an observer to listen for the change of a Preferences object.
     *
     * @param { PreferencesEvent } event - Indicates the callback when preferences changes.
     * @param { Callback1Argument<String> } callback - Indicates the callback function.
     * @throws { BusinessException } 15500000 - Inner error.
     * @throws { BusinessException } 15500019 - Failed to obtain the subscription service.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true
    ]
    public func on(event: PreferencesEvent, callback: Callback1Argument<String>): Unit {
        let tp = event.getValue()
        checkType(tp)
        synchronized(onOffMutex) {
            for (item in callbackList) {
                if (refEq(callback, item[0])) {
                    PREFERENCES_LOG.error("Preferences on failed: The same function is registered.")
                    return
                }
            }
            unsafe {
                let wrapper = {value: CString => callback.invoke(None, value.toString())}
                let lambdaData = Callback1Param<CString, Unit>(wrapper)
                try (ctp = LibC.mallocCString(tp).asResource()) {
                    let errCode = FfiOHOSPreferencesOn(getID(), ctp.value, lambdaData.getID())
                    throwIfNotSuccess(errCode)
                    callbackList.add((callback, lambdaData.getID()))
                }
            }
        }
    }

    /**
     * Unregisters an existing observer.
     *
     * @param { PreferencesEvent } event - Indicates the callback when preferences changes.
     * @param { Callback1Argument<String> } [callback] - Indicates the callback function.
     * @throws { BusinessException } 15500000 - Inner error.
     */
    @!APILevel[
        since: "22",
        syscap: "SystemCapability.DistributedDataManager.Preferences.Core",
        throwexception: true
    ]
    public func off(event: PreferencesEvent, callback!: ?Callback1Argument<String> = None): Unit {
        let tp = event.getValue()
        match (callback) {
            case Some(v) => offImpl(tp, v)
            case _ => offImpl(tp)
        }
    }

    /*
     * @throws { BusinessException } 15500000 - Inner error.
     */
    func offImpl(tp: String, callback: Callback1Argument<String>): Unit {
        checkType(tp)
        let funcId = Box<Int64>(0)
        synchronized(onOffMutex) {
            callbackList.removeIf {
                item => if (refEq(callback, item[0])) {
                    funcId.value = item[1]
                    true
                } else {
                    false
                }
            }
        }
        if (funcId.value == 0) {
            return
        }
        unsafe {
            try (ctp = LibC.mallocCString(tp).asResource()) {
                let errCode = FfiOHOSPreferencesOff(getID(), ctp.value, funcId.value)
                throwIfNotSuccess(errCode)
            }
        }
    }

    /*
     * @throws { BusinessException } 15500000 - Inner error.
     */
    func offImpl(tp: String): Unit {
        checkType(tp)
        unsafe {
            try (ctp = LibC.mallocCString(tp).asResource()) {
                let errCode = FfiOHOSPreferencesOffAll(getID(), ctp.value)
                throwIfNotSuccess(errCode)
            }
        }
        synchronized(onOffMutex) {
            callbackList.clear()
        }
    }
}

func throwIfNotSuccess(errCode: Int32): Unit {
    if (errCode != SUCCESS_CODE) {
        PREFERENCES_LOG.error(
            "Preferences failed: ${getCJErrorMsg(errCode)}${getNativeErrorMsg(errCode)} code: ${getCJErrorCode(errCode)} nativeErrorCode: ${errCode}"
        )
        throw BusinessException(getCJErrorCode(errCode), "Preferences failed: ${getCJErrorMsg(errCode)}")
    }
}
