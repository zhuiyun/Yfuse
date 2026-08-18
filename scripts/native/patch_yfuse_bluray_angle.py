#!/usr/bin/env python3
"""Adds Yfuse multi-angle state/JNI to the pinned optical stream at native-build time.

The source patch is deliberately exact-anchor based: upstream/source drift must fail the native build
instead of silently publishing an AAR whose Kotlin marker claims an angle feature that was not linked.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_yfuse_bluray_angle.py <stream_yfuse_bluray.c>")

path = Path(sys.argv[1])
text = path.read_text()

signature_old = 'jmethodID session_state = (*env)->GetMethodID(env, type, "onNativeSessionState", "(IIIIZZ)V");'
signature_new = 'jmethodID session_state = (*env)->GetMethodID(env, type, "onNativeSessionState", "(IIIIIIZZ)V");'
if signature_old not in text:
    raise SystemExit("unexpected Yfuse stream source: session-state JNI signature anchor missing")
text = text.replace(signature_old, signature_new, 1)

state_old = '''    int chapter_count = priv->title_info ? (int)priv->title_info->chapter_count : 0;\n    int chapter = priv->bd ? (int)bd_get_current_chapter(priv->bd) : 0;\n    if (chapter < 0 || (chapter_count > 0 && chapter >= chapter_count))\n        chapter = 0;\n\n    int attached = 0;'''
state_new = '''    int chapter_count = priv->title_info ? (int)priv->title_info->chapter_count : 0;\n    int chapter = priv->bd ? (int)bd_get_current_chapter(priv->bd) : 0;\n    if (chapter < 0 || (chapter_count > 0 && chapter >= chapter_count))\n        chapter = 0;\n    int angle_count = priv->title_info ? (int)priv->title_info->angle_count : 0;\n    int angle = priv->current_angle;\n    if (angle < 0 || (angle_count > 0 && angle >= angle_count))\n        angle = 0;\n\n    int attached = 0;'''
if state_old not in text:
    raise SystemExit("unexpected Yfuse stream source: session-state angle anchor missing")
text = text.replace(state_old, state_new, 1)

call_old = '''        (*env)->CallVoidMethod(env, priv->source->object, priv->source->session_state,\n                                (jint)priv->num_titles, (jint)title,\n                                (jint)chapter_count, (jint)chapter,\n                                (jboolean)(priv->menu_supported != 0),\n                                (jboolean)(priv->menu_active != 0));'''
call_new = '''        (*env)->CallVoidMethod(env, priv->source->object, priv->source->session_state,\n                                (jint)priv->num_titles, (jint)title,\n                                (jint)chapter_count, (jint)chapter,\n                                (jint)angle_count, (jint)angle,\n                                (jboolean)(priv->menu_supported != 0),\n                                (jboolean)(priv->menu_active != 0));'''
if call_old not in text:
    raise SystemExit("unexpected Yfuse stream source: session-state CallVoidMethod anchor missing")
text = text.replace(call_old, call_new, 1)

insert_anchor = '''JNIEXPORT jboolean JNICALL\nJava_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSendMenuCommand(JNIEnv *env, jclass clazz, jlong id, jint command)\n{'''
angle_function = '''JNIEXPORT jboolean JNICALL\nJava_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSelectAngle(JNIEnv *env, jclass clazz, jlong id, jint angle)\n{\n    (void)env;\n    (void)clazz;\n    if (angle < 0)\n        return JNI_FALSE;\n    yfuse_source *source = yfuse_source_acquire((int64_t)id);\n    if (!source)\n        return JNI_FALSE;\n    pthread_mutex_lock(&source->session_lock);\n    yfuse_bluray_priv *priv = source->active_priv;\n    int handled = 0;\n    if (priv && priv->bd && priv->title_info &&\n        angle < (jint)priv->title_info->angle_count) {\n        bd_seamless_angle_change(priv->bd, (unsigned)angle);\n        priv->current_angle = angle;\n        yfuse_refresh_title_info(priv);\n        yfuse_java_session_state(priv);\n        handled = 1;\n    }\n    pthread_mutex_unlock(&source->session_lock);\n    yfuse_source_release(source);\n    return handled ? JNI_TRUE : JNI_FALSE;\n}\n\n'''
if insert_anchor not in text:
    raise SystemExit("unexpected Yfuse stream source: menu-command JNI insertion anchor missing")
text = text.replace(insert_anchor, angle_function + insert_anchor, 1)

path.write_text(text)
