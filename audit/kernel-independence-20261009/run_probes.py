"""Run separately compiled DEX probes with isolated native dependency closures.

Uses only /data/local/tmp on the physical device; never installs an application.
These probes exercise pinned engine runtimes, not the application's complete UI.
"""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

OUT = pathlib.Path(__file__).resolve().parent
ROOT = OUT.parents[1]
SDK = pathlib.Path("D:/AndroidSDK")
JAVA = pathlib.Path("C:/Users/app-inkbird/.jdks/ms-21.0.9/bin")
ADB = SDK / "platform-tools/adb.exe"
SERIAL = "RF8M223V4MD"
REMOTE = "/data/local/tmp/yfuse-kernel-independence-20261009"
ANDROID = SDK / "platforms/android-37.0/android.jar"
CACHES = [ROOT / ".gradle-tmp/caches/modules-2/files-2.1",
          pathlib.Path("C:/Users/app-inkbird/.gradle/caches/modules-2/files-2.1")]

def run(args, name, timeout=60):
    result = subprocess.run([str(v) for v in args], cwd=ROOT, capture_output=True,
                            text=True, errors="replace", timeout=timeout)
    (OUT / (name + ".log")).write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(name + ": " + (result.stdout + result.stderr)[-3000:])
    return result.stdout

def artifact(group, name, version, extension="jar"):
    for cache in CACHES:
        files = [p for p in (cache / group / name / version).glob("*/*." + extension)
                 if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if files:
            return files[0]
    raise FileNotFoundError((group, name, version, extension))

def aar_jar(name):
    source = artifact("androidx.media3", name, "1.9.0", "aar")
    target = OUT / "deps" / (name + ".jar")
    target.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(source) as archive:
        target.write_bytes(archive.read("classes.jar"))
    return target

def parse_type(signature, offset):
    char = signature[offset]
    if char == "[":
        type_name, end = parse_type(signature, offset + 1)
        return type_name + "[]", end
    if char == "L":
        end = signature.index(";", offset)
        return signature[offset + 1:end].replace("/", "."), end + 1
    return {"V": "void", "I": "int", "J": "long", "Z": "boolean", "B": "byte", "F": "float", "D": "double"}[char], offset + 1

native_source = (ROOT / "scripts/native/ycore_demux_jni.cpp").read_text(encoding="utf-8")
methods = re.findall(r'\{"(native\w+)", "([^"\n]+)"', native_source)
declarations = []
for name, signature in methods:
    params, offset = [], 1
    while signature[offset] != ")":
        type_name, offset = parse_type(signature, offset)
        params.append(type_name + " arg" + str(len(params)))
    return_type, _ = parse_type(signature, offset + 1)
    declarations.append("public static native " + return_type + " " + name + "(" + ",".join(params) + ");")
bridge = OUT / "FfmpegNativeBridge.java"
bridge.write_text("package com.yfuse.core2.android;\npublic final class FfmpegNativeBridge {\n" +
                  "\n".join(declarations) + "\n}\n", encoding="utf-8")
gpu = OUT / "AndroidYCoreGpuNativeBridge.java"
gpu.write_text("package com.yfuse.core2.android; public final class AndroidYCoreGpuNativeBridge { public static native int nativeGpuApiVersion(); public static native long nativeProbeGpuFeatures(); }", encoding="utf-8")
stdlib = artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20")
media_jars = [aar_jar(name) for name in ["media3-common", "media3-container", "media3-decoder",
             "media3-datasource", "media3-exoplayer", "media3-extractor", "media3-database"]]
media_jars += [artifact("com.google.guava", "guava", "33.3.1-android"),
               artifact("com.google.guava", "failureaccess", "1.0.2")]
sets = {
    "mpv": {"source": [OUT / "MpvProbe.java"], "jars": [OUT / "mpv-classes.jar", stdlib], "main": "MpvProbe"},
    "mdk": {"source": [OUT / "MdkProbe.java", ROOT / "mdkAndroid/src/main/java/com/mediadevkit/sdk/MDKPlayer.java"], "jars": [], "main": "MdkProbe"},
    "ycore": {"source": [OUT / "YCoreProbe.java", bridge, gpu], "jars": [], "main": "YCoreProbe"},
    "exo": {"source": [OUT / "ExoProbe.java"], "jars": media_jars, "main": "ExoProbe"},
}
sample = ROOT / "audit/project-audit-20260811/cold-start.mp4"
results = {"device": SERIAL, "sample": str(sample), "sampleSha256": hashlib.sha256(sample.read_bytes()).hexdigest(),
           "nativeLibrariesFrom": "Yfuse 1.1.7 versionCode 269 full APK", "applicationInstalled": False,
           "scope": "Independent runtime loading, baseline video output or software decoding and teardown; not full app UI or format coverage", "engines": {}}
if sys.argv[1:] and (OUT / "device-results.json").exists():
    results = json.loads((OUT / "device-results.json").read_text(encoding="utf-8"))
run([ADB, "-s", SERIAL, "shell", "mkdir", "-p", REMOTE], "device-mkdir")
run([ADB, "-s", SERIAL, "push", sample, REMOTE + "/sample.mp4"], "sample-push")
for name, spec in sets.items():
    if sys.argv[1:] and name not in sys.argv[1:]:
        continue
    print("BUILD", name, flush=True)
    folder = OUT / "payload" / name
    folder.mkdir(parents=True, exist_ok=True)
    classes = OUT / (name + "-classes")
    classes.mkdir(exist_ok=True)
    try:
        classpath = os.pathsep.join(str(p) for p in [ANDROID] + spec["jars"])
        run([JAVA / "javac.exe", "-source", "8", "-target", "8", "-cp", classpath, "-d", classes,
             OUT / "ProbeSupport.java", OUT / "ProbeEntry.java", *spec["source"]], name + "-javac")
        dex = folder / "probe.zip"
        run([JAVA / "java.exe", "-cp", SDK / "build-tools/36.1.0/lib/d8.jar", "com.android.tools.r8.D8",
             "--min-api", "26", "--lib", ANDROID, "--output", dex,
             *classes.rglob("*.class"), *spec["jars"]], name + "-d8")
        run([ADB, "-s", SERIAL, "push", folder, REMOTE], name + "-push")
        command = ("MDK_LOG=1 CLASSPATH=" + REMOTE + "/" + name + "/probe.zip LD_LIBRARY_PATH=" + REMOTE + "/" + name +
                   " app_process -Djava.library.path=" + REMOTE + "/" + name + ":/system/lib64:/vendor/lib64 /system/bin ProbeEntry " + spec["main"] +
                   " " + REMOTE + "/" + name + " " + REMOTE + "/sample.mp4")
        output = run([ADB, "-s", SERIAL, "shell", command], name + "-device")
        print(output, flush=True)
        if "PASS " + name + " independent" not in output:
            raise AssertionError("Missing independent playback/decode evidence")
        results["engines"][name] = {"status": "passed", "output": output, "dexSha256": hashlib.sha256(dex.read_bytes()).hexdigest()}
    except Exception as error:
        results["engines"][name] = {"status": "failed", "error": str(error)}
        print("FAIL", name, str(error), flush=True)
    (OUT / "device-results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
if any(result["status"] != "passed" for result in results["engines"].values()):
    sys.exit(1)
