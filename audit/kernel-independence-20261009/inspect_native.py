"""Inspect dependency closure in the actual delivered APK; no application is changed."""
import hashlib
import json
import pathlib
import struct
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent
APK = ROOT / "artifacts-local/releases/schedule-1.1.7-269/Yfuse-1.1.7-full-arm64.apk"

def elf_needed(data):
    assert data[:4] == b"\x7fELF" and data[4:6] == b"\x02\x01"
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", data, 54)
    segments, dynamic = [], None
    for i in range(phnum):
        kind, flags, offset, addr, paddr, size, memsize, align = struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize)
        if kind == 1:
            segments.append((addr, size, offset))
        if kind == 2:
            dynamic = (offset, size)
    if dynamic is None:
        return []
    needed, straddr = [], None
    for off in range(dynamic[0], sum(dynamic), 16):
        tag, value = struct.unpack_from("<qQ", data, off)
        if tag == 0:
            break
        if tag == 1:
            needed.append(value)
        if tag == 5:
            straddr = value
    base = next(offset + straddr - addr for addr, size, offset in segments if addr <= straddr < addr + size)
    return [data[base + value:data.index(b"\0", base + value)].decode() for value in needed]

with zipfile.ZipFile(APK) as archive:
    libs = {pathlib.PurePosixPath(name).name: archive.read(name) for name in archive.namelist()
            if name.startswith("lib/arm64-v8a/") and name.endswith(".so")}
    dex = [name for name in archive.namelist() if name.endswith(".dex")]
graph = {name: elf_needed(data) for name, data in libs.items()}
engines = {"mpv": ["libplayer.so", "libmpv.so"],
           "mdk": ["libffmpeg.so", "libmdk.so", "libyfuse-mdk-jni.so", "libdav1d.so"],
           "ycore": ["libycore_demux.so", "libycore_gpu.so"]}
report = {"apk": str(APK), "apkSha256": hashlib.sha256(APK.read_bytes()).hexdigest(),
          "dex": dex, "libraries": {}, "engines": {}}
for name, data in libs.items():
    report["libraries"][name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "needed": graph[name]}
for engine, entries in engines.items():
    ordered, external, seen = [], set(), set()
    def visit(name):
        if name in seen:
            return
        seen.add(name)
        if name not in libs:
            external.add(name)
            return
        for dep in graph[name]:
            visit(dep)
        ordered.append(name)
    for entry in entries:
        if entry in libs:
            visit(entry)
    folder = OUT / "payload" / engine
    folder.mkdir(parents=True, exist_ok=True)
    for name in ordered:
        (folder / name).write_bytes(libs[name])
    (folder / "load-order.txt").write_text("\n".join(ordered), encoding="utf-8")
    report["engines"][engine] = {"entryLibraries": entries, "loadOrder": ordered, "externalDependencies": sorted(external)}
    print(engine, ":", ", ".join(ordered))
(OUT / "native-dependencies.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
with zipfile.ZipFile(ROOT / "composeApp/libs/libmpv-release.aar") as archive:
    (OUT / "mpv-classes.jar").write_bytes(archive.read("classes.jar"))
print("APK SHA-256", report["apkSha256"])
