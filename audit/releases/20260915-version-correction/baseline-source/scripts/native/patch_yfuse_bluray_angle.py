#!/usr/bin/env python3
"""Adds Yfuse multi-angle state/JNI to the generated optical native sources.

The patch is deliberately exact-anchor based: source drift must fail the native build instead of
silently publishing an AAR whose Kotlin/UI layer advertises an angle feature that was not linked.
"""
from pathlib import Path
import sys

if len(sys.argv) != 5:
    raise SystemExit(
        "usage: patch_yfuse_bluray_angle.py <stream_yfuse_bluray.c> <stream_yfuse_bdmv.c> "
        "<YfuseBluRayRegistry.java> <YfuseBdmvRegistry.java>"
    )

stream_path = Path(sys.argv[1])
bdmv_path = Path(sys.argv[2])
registry_paths = [Path(sys.argv[3]), Path(sys.argv[4])]
text = stream_path.read_text()

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
stream_path.write_text(text)

bdmv = bdmv_path.read_text()
bdmv_define_anchor = '''#define Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSendMenuCommand \\\n    Java_dev_yfuse_mpv_YfuseBdmvRegistry_nativeSendMenuCommand\n'''
bdmv_define_angle = '''#define Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSelectAngle \\\n    Java_dev_yfuse_mpv_YfuseBdmvRegistry_nativeSelectAngle\n'''
if bdmv_define_anchor not in bdmv:
    raise SystemExit("unexpected BDMV source: JNI namespace define anchor missing")
bdmv = bdmv.replace(bdmv_define_anchor, bdmv_define_angle + bdmv_define_anchor, 1)

bdmv_undef_anchor = '''#undef Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSendMenuCommand\n'''
bdmv_undef_angle = '''#undef Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSelectAngle\n'''
if bdmv_undef_anchor not in bdmv:
    raise SystemExit("unexpected BDMV source: JNI namespace undef anchor missing")
bdmv = bdmv.replace(bdmv_undef_anchor, bdmv_undef_angle + bdmv_undef_anchor, 1)
bdmv_path.write_text(bdmv)

for registry_path in registry_paths:
    java = registry_path.read_text()
    public_anchor = '''    public static boolean sendMenuCommand(long id, int command) {\n        return id > 0L && nativeSendMenuCommand(id, command);\n    }\n'''
    public_angle = '''    public static boolean selectAngle(long id, int angle) {\n        return id > 0L && angle >= 0 && nativeSelectAngle(id, angle);\n    }\n\n'''
    if public_anchor not in java:
        raise SystemExit(f"unexpected registry source: public menu anchor missing in {registry_path}")
    java = java.replace(public_anchor, public_angle + public_anchor, 1)

    native_anchor = '''    private static native boolean nativeSendMenuCommand(long id, int command);\n'''
    native_angle = '''    private static native boolean nativeSelectAngle(long id, int angle);\n'''
    if native_anchor not in java:
        raise SystemExit(f"unexpected registry source: native menu anchor missing in {registry_path}")
    java = java.replace(native_anchor, native_angle + native_anchor, 1)
    registry_path.write_text(java)
