#!/usr/bin/env python3
"""Routes the Android wrapper's legacy `vo=gpu` requests to mpv's gpu-next renderer.

Yfuse's Kotlin engine deliberately keeps its stable backend API and asks the wrapper for `gpu`.
Profile 7 FEL composition exists in mpv's gpu-next/libplacebo renderer, so the custom native AAR
translates only that exact VO value. `vo=null` teardown and every other property remain untouched.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_yfuse_gpu_next.py <property.cpp>")

path = Path(sys.argv[1])
text = path.read_text()
if "yfuse_effective_vo" in text:
    raise SystemExit("gpu-next wrapper patch is already present")

include_old = "#include <cstdlib>\n"
include_new = "#include <cstdlib>\n#include <cstring>\n"
if include_old not in text:
    raise SystemExit("unexpected property.cpp: include anchor missing")
text = text.replace(include_old, include_new, 1)

helper_anchor = "#include \"globals.h\"\n\n"
helper = helper_anchor + r'''static const char *yfuse_effective_vo(const char *property, const char *value) {
    if (property && value && std::strcmp(property, "vo") == 0 && std::strcmp(value, "gpu") == 0)
        return "gpu-next";
    return value;
}

'''
if helper_anchor not in text:
    raise SystemExit("unexpected property.cpp: helper anchor missing")
text = text.replace(helper_anchor, helper, 1)

option_old = r'''    const char *option = env->GetStringUTFChars(joption, nullptr);
    const char *value = env->GetStringUTFChars(jvalue, nullptr);

    int result = mpv_set_option_string(mpv_instance->mpv, option, value);
'''
option_new = r'''    const char *option = env->GetStringUTFChars(joption, nullptr);
    const char *value = env->GetStringUTFChars(jvalue, nullptr);
    const char *effective = yfuse_effective_vo(option, value);

    int result = mpv_set_option_string(mpv_instance->mpv, option, effective);
'''
if option_old not in text:
    raise SystemExit("unexpected property.cpp: set-option anchor missing")
text = text.replace(option_old, option_new, 1)

property_old = r'''jni_func(void, nativeSetPropertyString, jlong instance, jstring jproperty, jstring jvalue) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    const char *value = env->GetStringUTFChars(jvalue, nullptr);
    common_set_property(env, mpv_instance->mpv, jproperty, MPV_FORMAT_STRING, &value);
    env->ReleaseStringUTFChars(jvalue, value);
}
'''
property_new = r'''jni_func(void, nativeSetPropertyString, jlong instance, jstring jproperty, jstring jvalue) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    if (!mpv_instance->mpv) {
        die(env, "set_property called but mpv is not initialized");
        return;
    }
    const char *property = env->GetStringUTFChars(jproperty, nullptr);
    const char *value = env->GetStringUTFChars(jvalue, nullptr);
    const char *effective = yfuse_effective_vo(property, value);
    int result = mpv_set_property_string(mpv_instance->mpv, property, effective);
    if (result < 0)
        ALOGE("mpv_set_property_string(%s) returned error %s", property, mpv_error_string(result));
    env->ReleaseStringUTFChars(jproperty, property);
    env->ReleaseStringUTFChars(jvalue, value);
}
'''
if property_old not in text:
    raise SystemExit("unexpected property.cpp: set-property-string anchor missing")
text = text.replace(property_old, property_new, 1)

required = (
    'return "gpu-next";',
    'mpv_set_option_string(mpv_instance->mpv, option, effective)',
    'mpv_set_property_string(mpv_instance->mpv, property, effective)',
)
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("gpu-next wrapper patch is incomplete: " + ", ".join(missing))

path.write_text(text)
