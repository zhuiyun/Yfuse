"""Validate all JNI registrations and retained startup packets on the attached Android device.

Runs from /data/local/tmp via app_process; does not install or replace the user's app.
"""
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import wave
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
STAGE = pathlib.Path(__file__).parent / "device"
STAGE.mkdir(exist_ok=True)
JAVA = pathlib.Path("C:/Users/app-inkbird/.jdks/ms-21.0.9/bin")
SDK = pathlib.Path("D:/AndroidSDK")
ADB = SDK / "platform-tools/adb.exe"
REMOTE = "/data/local/tmp/yfuse-playback-optimization-20260920"

def run(*args):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, errors="replace")
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout + result.stderr

def parse_type(signature, index):
    if signature[index] == "[":
        name, end = parse_type(signature, index + 1)
        return name + "[]", end
    if signature[index] == "L":
        end = signature.index(";", index)
        return signature[index + 1:end].replace("/", "."), end + 1
    return {"V": "void", "I": "int", "J": "long", "Z": "boolean", "B": "byte", "F": "float", "D": "double"}[signature[index]], index + 1

cpp = (ROOT / "scripts/native/ycore_demux_jni.cpp").read_text()
methods = re.findall(r'\{"(native\w+)", "(\([^\"]+|\(\)[^\"]+)"', cpp)
assert len(methods) > 40
declarations = []
for name, descriptor in methods:
    params = []
    index = 1
    while descriptor[index] != ")":
        typename, index = parse_type(descriptor, index)
        params.append(f"{typename} arg{len(params)}")
    returns, end = parse_type(descriptor, index + 1)
    declarations.append(f"public static native {returns} {name}({', '.join(params)});")

source = r'''
package com.yfuse.core2.android;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
public class FfmpegNativeBridge {
DECLARATIONS
static String[] headers = new String[0];
static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
static List<String> packets(String file, boolean bounded) throws Exception {
    long token = nativeCreateCancellation();
    long handle = 0;
    try {
        handle = bounded ? nativeOpenWithAnalysis(file, headers, headers, false, token)
            : nativeOpenCancellable(file, headers, headers, false, token);
        check(handle > 0, "Open failed: " + nativeLastOpenFailure());
        int count = nativeTrackCount(handle);
        check(count > 0, "No tracks");
        int[] tracks = new int[count];
        for(int i = 0; i < count; i++) tracks[i] = i;
        nativeSelectTracks(handle, tracks);
        List<String> result = new ArrayList<>();
        result.add("tracks=" + count);
        ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
        for(int i = 0; i < 128; i++) {
            buffer.clear();
            long[] packet = nativeReadPacket(handle, buffer);
            check(packet != null, "No packet metadata");
            if (packet[2] <= 0) break;
            byte[] bytes = new byte[(int)packet[2]];
            buffer.get(bytes);
            result.add(Arrays.toString(packet) + Arrays.toString(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        check(result.size() > 1, "No media packets");
        return result;
    } finally {
        if (handle > 0) nativeClose(handle);
        nativeReleaseCancellation(token);
    }
}
public static void main(String[] args) throws Exception {
    System.load(args[0] + "/libycore_demux.so");
    check(nativeDemuxHandleContractVersion() >= 2, "Handle ABI");
    for (int i = 1; i < args.length; i++) {
        List<String> full = packets(args[i], false);
        List<String> bounded = packets(args[i], true);
        check(full.equals(bounded), "Startup analysis lost or changed retained packets");
        System.out.println("PASS " + (i == 1 ? "PCM" : "MP4") + " retained_packets=" + (full.size() - 1));
    }
    long token = nativeCreateCancellation();
    try {
        nativeCancelDemux(token);
        long handle = nativeOpenWithAnalysis(args[1], headers, headers, false, token);
        if (handle > 0) nativeClose(handle);
        check(handle < 0, "Cancelled bounded open returned a live handle");
    } finally { nativeReleaseCancellation(token); }
    System.out.println("PASS cancelled_startup_analysis");
    System.out.println("PASS all_JNI_methods_registered");
}
}
'''.replace("DECLARATIONS", "\n".join(declarations))
java_file = STAGE / "FfmpegNativeBridge.java"
java_file.write_text(source)
classes = STAGE / "classes"
classes.mkdir(exist_ok=True)
run(JAVA / "javac.exe", "-source", "8", "-target", "8", "-d", classes, java_file)
dex = STAGE / "smoke.zip"
run(JAVA / "java.exe", "-cp", SDK / "build-tools/36.1.0/lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "26", "--output", dex, *classes.rglob("*.class"))
payload = STAGE / "payload"
payload.mkdir(exist_ok=True)
with zipfile.ZipFile(ROOT / "composeApp/libs/ycore-native.aar") as aar:
    for name in aar.namelist():
        if name.startswith("jni/arm64-v8a/") and name.endswith(".so"):
            (payload / pathlib.PurePosixPath(name).name).write_bytes(aar.read(name))
shutil.copyfile(dex, payload / "smoke.zip")
with wave.open(str(payload / "sample.wav"), "wb") as audio:
    audio.setparams((2, 2, 48000, 0, "NONE", "not compressed"))
    audio.writeframes(bytes(range(256)) * 1500)
shutil.copyfile(ROOT / "audit/project-audit-20260811/cold-start.mp4", payload / "sample.mp4")
run(ADB, "shell", "mkdir", "-p", REMOTE)
for file in payload.iterdir():
    run(ADB, "push", file, REMOTE + "/" + file.name)
output = run(ADB, "shell", f"CLASSPATH={REMOTE}/smoke.zip LD_LIBRARY_PATH={REMOTE} app_process /system/bin com.yfuse.core2.android.FfmpegNativeBridge {REMOTE} {REMOTE}/sample.wav {REMOTE}/sample.mp4")
(STAGE / "result.txt").write_text(output, encoding="utf-8")
print(output)
assert "PASS all_JNI_methods_registered" in output
(STAGE / "verification.json").write_text(json.dumps({"jni_methods": len(methods), "result": output, "demux_sha256": hashlib.sha256((payload / "libycore_demux.so").read_bytes()).hexdigest()}, indent=2))
