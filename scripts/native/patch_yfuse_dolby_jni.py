#!/usr/bin/env python3
"""Adds JNI accessors for Yfuse's libmpv Dolby render evidence."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_yfuse_dolby_jni.py <libmpv main.cpp>")

path = Path(sys.argv[1])
text = path.read_text()
marker = "Java_dev_yfuse_mpv_YfuseMpvCapabilities_nativeDolbyVisionEvidence"
if marker in text:
    raise SystemExit("Dolby JNI patch is already present")

# `vo_gpu_next.c` exports these with default visibility from libmpv.so. libplayer.so already links
# against libmpv, so the small JNI wrapper does not duplicate any render-state logic.
append = r'''

extern "C" uint64_t yfuse_mpv_dolby_generation(void);
extern "C" uint32_t yfuse_mpv_dolby_evidence(void);

extern "C" JNIEXPORT jlong JNICALL
Java_dev_yfuse_mpv_YfuseMpvCapabilities_nativeDolbyVisionGeneration(JNIEnv *, jclass)
{
    return static_cast<jlong>(yfuse_mpv_dolby_generation());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_yfuse_mpv_YfuseMpvCapabilities_nativeDolbyVisionEvidence(JNIEnv *, jclass)
{
    return static_cast<jint>(yfuse_mpv_dolby_evidence());
}
'''

text += append
path.write_text(text)
