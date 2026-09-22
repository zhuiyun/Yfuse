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

/**
 * Created on 2024/7/5
 */
/**
 * Created on 2024/1/29
 */
package ohos_app_cangjie_entry

import kit.ArkData.*

import std.runtime.*
import std.sync.*
import std.time.*

import std.collection.*
import ohos.hilog.Hilog
import ohos.callback_invoke.*
import ohos.business_exception.*

import std.unittest.*
import std.unittest.common.*
import std.unittest.testmacro.*


class Callback <: Callback1Argument<String> {
    public func invoke(err: ?BusinessException, arg: String) {
        Hilog.info(0, "test", "=========callback========= ${arg.toString()}======================")
    }
}


@Test
class Test_preferences {

    override func afterAll(): Unit {
        GC()
        sleep(Duration.second)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_context() {
        @Expect(MAX_KEY_LENGTH, UInt32(1024))
        @Expect(MAX_VALUE_LENGTH, UInt32(16 * 1024 * 1024))
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_getPreferences() {
        var str = "myStore"
        var str1 = "otherStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        var b = Preferences.getPreferences(getAbilityContext(), PreferencesOptions("otherStore"))
        Preferences.deletePreferences(getAbilityContext(), str)
        Preferences.deletePreferences(getAbilityContext(), str1)
        @Expect(0, 0)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_deletePreferences() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("key", PreferencesValueType.Integer(980))
        // preferces放入key并且立马查询，查询不到直接报错
        match (a.get("key", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 980) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        Preferences.deletePreferences(getAbilityContext(), str)
        // 删除preferences之后，使用原先的引用应该还能查出来
        match (a.get("key", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 980) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        // 删除preferences之后，使用新的引用不应该查出来
        var b = Preferences.getPreferences(getAbilityContext(), str)
        match (b.get("key", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 0) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_removePreferences() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("key", PreferencesValueType.Integer(980))
        // preferces放入key并且立马查询，查询不到直接报错
        match (a.get("key", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 980) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        Preferences.removePreferencesFromCache(getAbilityContext(), str)
        // 移除preferences缓存之后，使用原先的引用应该还能查出来
        match (a.get("key", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 980) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        // 删除preferences之后，使用新的引用不应该查出来
        var b = Preferences.getPreferences(getAbilityContext(), str)
        match (b.get("key", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 0) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_put_get() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("key01", PreferencesValueType.Integer(3))
        a.put("key02", PreferencesValueType.Integer(5))
        a.put("key03", PreferencesValueType.Double(5.77))
        a.put("key04", PreferencesValueType.StringData("今天天气真好"))
        a.put("key05", PreferencesValueType.BoolData(true))
        a.put("key06", PreferencesValueType.BoolArray([true, false, true, false]))
        a.put(
            "key07",
            PreferencesValueType.DoubleArray(
                [3.14, 15.9, 26.53, 58.979, 32.384, 626.433, 8327.950288, 41.971, 493.9937510, 58209.74944])
        )
        a.put("key08", PreferencesValueType.StringArray(["这个，", "按理说不应该", "出错", "如果出错了", "就gg"]))
        match (a.get("key01", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 3) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        match (a.get("key02", PreferencesValueType.Integer(0))) {
            case PreferencesValueType.Integer(n) =>
                if (n != 5) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        match (a.get("key03", PreferencesValueType.Double(0.0))) {
            case PreferencesValueType.Double(n) =>
                if (n != 5.77) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        match (a.get("key04", PreferencesValueType.StringData("0"))) {
            case PreferencesValueType.StringData(n) =>
                if (n != "今天天气真好") {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        match (a.get("key05", PreferencesValueType.BoolData(false))) {
            case PreferencesValueType.BoolData(n) =>
                if (n != true) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        match (a.get("key06", PreferencesValueType.BoolArray([false]))) {
            case PreferencesValueType.BoolArray(n) =>
                if (n != [true, false, true, false]) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        match (a.get("key07", PreferencesValueType.DoubleArray([0.77]))) {
            case PreferencesValueType.DoubleArray(n) =>
                if (n != [3.14, 15.9, 26.53, 58.979, 32.384, 626.433, 8327.950288, 41.971, 493.9937510, 58209.74944]) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        match (a.get("key08", PreferencesValueType.StringArray(["wu"]))) {
            case PreferencesValueType.StringArray(n) =>
                if (n != ["这个，", "按理说不应该", "出错", "如果出错了", "就gg"]) {
                    throw Exception()
                }
            case _ => throw Exception()
        }
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_flush() {
        var str = "myStore1"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("天气怎么样?", PreferencesValueType.StringData("今天天气真好"))
        a.flush()
        Preferences.removePreferencesFromCache(getAbilityContext(), str)
        var b = Preferences.getPreferences(getAbilityContext(), str)
        if (!b.has("天气怎么样?")) {
            throw Exception()
        }
        Preferences.deletePreferences(getAbilityContext(), str)
        var c = Preferences.getPreferences(getAbilityContext(), str)
        if (c.has("天气怎么样?")) {
            throw Exception()
        }
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_clear() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("天气怎么样?", PreferencesValueType.StringData("今天天气真好"))
        a.flush()
        a.put("今天吃什么?", PreferencesValueType.StringData("吃麻辣香锅"))
        a.clear()
        var b = Preferences.getPreferences(getAbilityContext(), str)
        if (a.has("天气怎么样?")) {
            throw Exception()
        }
        if (a.has("今天吃什么?")) {
            throw Exception()
        }
        if (b.has("天气怎么样?")) {
            throw Exception()
        }
        if (b.has("今天吃什么?")) {
            throw Exception()
        }
        Preferences.removePreferencesFromCache(getAbilityContext(), str)
        var c = Preferences.getPreferences(getAbilityContext(), str)
        if (!c.has("天气怎么样?")) {
            throw Exception()
        }
        Preferences.deletePreferences(getAbilityContext(), str)
        var d = Preferences.getPreferences(getAbilityContext(), str)
        if (d.has("天气怎么样?")) {
            throw Exception()
        }
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_on_off() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("kkk1", PreferencesValueType.StringData("vvv1"))
        var c = Callback()

        a.on(PreferencesEvent.PreferencesChange, c)
        a.on(PreferencesEvent.PreferencesChange, c)
        a.flush()
        a.off(PreferencesEvent.PreferencesChange, callback: c)
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_off() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("kkk1", PreferencesValueType.StringData("vvv1"))
        var c = Callback()
        a.on(PreferencesEvent.PreferencesChange, c)
        a.flush()
        a.off(PreferencesEvent.PreferencesChange)
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_delete() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("kkk1", PreferencesValueType.StringData("vvv1"))
        a.delete("kkk1")
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_delete_error() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        try {
            a.delete("")
            @Expect(false)
        } catch (e: BusinessException) {
            @Expect(e.code, 401)
        }
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_deletePreferences_error() {
        var str = ""
        try {
            Preferences.deletePreferences(getAbilityContext(), str)
            @Expect(false)
        } catch (e: BusinessException) {
            @Expect(e.code, 15500000)
        }
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_removePreferencesFromCache_error() {
        var str = ""
        try {
            Preferences.removePreferencesFromCache(getAbilityContext(), str)
            @Expect(false)
        } catch (e: BusinessException) {
            @Expect(e.code, 15500000)
        }
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_put_error() {
        var str = "myStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        try {
            a.put("", PreferencesValueType.Integer(980))
            @Expect(false)
        } catch (e: BusinessException) {
            @Expect(e.code, 401)
        }
    }


    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_option() {
        var str = "myStore"
        var str1 = "otherStore"
        var str2 = "GroupId"
        try {
            Preferences.getPreferences(getAbilityContext(), PreferencesOptions("otherStore", dataGroupId: "GroupId"))
        } catch (e: BusinessException) {
            Hilog.error(0, "test", "e========== ${e.message}======================")
            @Expect(e.code, 15501002)
        }
        @Expect(0, 0)
    }

    @TestCase
    @Tag[APILevel22, TestLevel0]
    func test_CPreferencesValueTypes() {
        var str = "myStore"
        var str1 = "otherStore"
        var a = Preferences.getPreferences(getAbilityContext(), str)
        a.put("key01", PreferencesValueType.Integer(3))
        a.put("key02", PreferencesValueType.Integer(5))
        a.put("key03", PreferencesValueType.Double(5.77))
        a.put("key04", PreferencesValueType.StringData("今天天气真好"))
        a.put("key05", PreferencesValueType.BoolData(true))
        a.put("key06", PreferencesValueType.BoolArray([true, false, true, false]))
        a.getAll()
        Preferences.deletePreferences(getAbilityContext(), str)
        @Expect(true)
    }

}
