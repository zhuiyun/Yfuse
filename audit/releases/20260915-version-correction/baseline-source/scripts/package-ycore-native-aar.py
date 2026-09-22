#!/usr/bin/env python3
"""Build a minimal YCore AAR from the native dependency closure of libycore_demux."""

from __future__ import annotations

import argparse
import hashlib
import io
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from collections.abc import Callable, Iterable, Mapping


SYSTEM_LIBRARIES = frozenset(
    {
        "libandroid.so",
        "libc.so",
        "libdl.so",
        "libEGL.so",
        "libGLESv2.so",
        "libGLESv3.so",
        "libjnigraphics.so",
        "liblog.so",
        "libm.so",
        "libmediandk.so",
        "libOpenSLES.so",
        "libvulkan.so",
        "libz.so",
    },
)
FORBIDDEN_LIBRARIES = frozenset({"libmpv.so", "libplayer.so", "libmdk.so"})
COMPANION_LIBRARIES = frozenset({"libc++_shared.so"})
SEED_LIBRARY = "libycore_demux.so"
OPTIONAL_SEED_LIBRARIES = ("libycore_gpu.so",)
GPU_LIBRARY = "libycore_gpu.so"
NEEDED_PATTERN = re.compile(r"Shared library: \[([^\]]+)]")
SAFE_SEGMENT_PATTERN = re.compile(r"[A-Za-z0-9_.+-]+")


class PackagingError(RuntimeError):
    pass


def parse_needed(dynamic_section: str) -> tuple[str, ...]:
    return tuple(NEEDED_PATTERN.findall(dynamic_section))


def dependency_closure(
    available: Iterable[str],
    needed: Callable[[str], Iterable[str]],
    seeds: Iterable[str] = (SEED_LIBRARY,),
) -> tuple[str, ...]:
    available_set = set(available)
    if SEED_LIBRARY not in available_set:
        raise PackagingError(f"missing {SEED_LIBRARY}")

    selected: set[str] = set()
    pending = list(seeds)
    while pending:
        library = pending.pop()
        if library in selected:
            continue
        if library in FORBIDDEN_LIBRARIES:
            raise PackagingError(f"YCore dependency closure contains legacy engine {library}")
        if library not in available_set:
            raise PackagingError(f"YCore dependency closure is missing {library}")
        selected.add(library)
        for dependency in needed(library):
            if dependency in SYSTEM_LIBRARIES:
                continue
            if dependency in FORBIDDEN_LIBRARIES:
                raise PackagingError(
                    f"{library} links forbidden legacy engine {dependency}",
                )
            if dependency not in available_set:
                raise PackagingError(f"{library} links unavailable {dependency}")
            pending.append(dependency)
    return tuple(sorted(selected))


def readelf_needed(readelf: str, library: pathlib.Path) -> tuple[str, ...]:
    result = subprocess.run(
        [readelf, "-dW", str(library)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise PackagingError(f"readelf failed for {library.name}: {detail}")
    return parse_needed(result.stdout)


def deterministic_info(name: str, mode: int = 0o644) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = mode << 16
    return info


def empty_classes_jar() -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(
            deterministic_info("META-INF/MANIFEST.MF"),
            "Manifest-Version: 1.0\nCreated-By: YCore native packager\n\n",
        )
    return output.getvalue()


def grouped_native_entries(archive: zipfile.ZipFile) -> dict[str, dict[str, str]]:
    grouped: dict[str, dict[str, str]] = {}
    for name in archive.namelist():
        parts = pathlib.PurePosixPath(name).parts
        if len(parts) != 3 or parts[0] != "jni" or not parts[2].endswith(".so"):
            continue
        abi, filename = parts[1], parts[2]
        if (
            abi in {".", ".."}
            or filename in {".", ".."}
            or SAFE_SEGMENT_PATTERN.fullmatch(abi) is None
            or SAFE_SEGMENT_PATTERN.fullmatch(filename) is None
        ):
            raise PackagingError(f"unsafe native archive entry: {name}")
        existing = grouped.setdefault(abi, {}).get(filename)
        if existing is not None and existing != name:
            raise PackagingError(f"duplicate {filename} for ABI {abi}")
        grouped[abi][filename] = name
    return grouped


def package_ycore_aar(
    source_aar: pathlib.Path,
    output_aar: pathlib.Path,
    provenance: pathlib.Path,
    notice: pathlib.Path,
    readelf: str,
) -> Mapping[str, tuple[str, ...]]:
    if not source_aar.is_file():
        raise PackagingError(f"source AAR does not exist: {source_aar}")
    if not provenance.is_file():
        raise PackagingError(f"provenance manifest does not exist: {provenance}")
    if not notice.is_file():
        raise PackagingError(f"third-party notice does not exist: {notice}")

    selected_by_abi: dict[str, tuple[str, ...]] = {}
    staged_bytes: dict[tuple[str, str], bytes] = {}
    with zipfile.ZipFile(source_aar, "r") as source, tempfile.TemporaryDirectory(
        prefix="ycore-native-aar.",
    ) as temporary:
        grouped = grouped_native_entries(source)
        for abi, entries in sorted(grouped.items()):
            if SEED_LIBRARY not in entries:
                continue
            abi_dir = pathlib.Path(temporary, abi)
            abi_dir.mkdir(parents=True)
            for filename, archive_name in entries.items():
                path = abi_dir / filename
                path.write_bytes(source.read(archive_name))
            selected = dependency_closure(
                entries,
                lambda filename, root=abi_dir: readelf_needed(readelf, root / filename),
                (
                    SEED_LIBRARY,
                    *(seed for seed in OPTIONAL_SEED_LIBRARIES if seed in entries),
                ),
            )
            selected_by_abi[abi] = selected
            for filename in selected:
                staged_bytes[(abi, filename)] = (abi_dir / filename).read_bytes()

    if "arm64-v8a" not in selected_by_abi:
        raise PackagingError("source AAR has no arm64-v8a YCore bridge")

    manifest = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        'package="com.yfuse.ycore.nativecarrier" />\n'
    )
    output_aar.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = output_aar.with_name(f".{output_aar.name}.tmp")
    try:
        with zipfile.ZipFile(temporary_output, "w") as output:
            output.writestr(deterministic_info("AndroidManifest.xml"), manifest)
            output.writestr(deterministic_info("classes.jar"), empty_classes_jar())
            output.writestr(deterministic_info("R.txt"), "")
            output.writestr(deterministic_info("consumer-rules.pro"), "")
            output.writestr(
                deterministic_info("META-INF/ycore-native-sources.txt"),
                provenance.read_text(encoding="utf-8"),
            )
            output.writestr(
                deterministic_info("META-INF/NOTICE"),
                notice.read_text(encoding="utf-8"),
            )
            for (abi, filename), content in sorted(staged_bytes.items()):
                output.writestr(
                    deterministic_info(f"jni/{abi}/{filename}", mode=0o755),
                    content,
                )
        temporary_output.replace(output_aar)
    finally:
        temporary_output.unlink(missing_ok=True)

    digest = hashlib.sha256(output_aar.read_bytes()).hexdigest()
    output_aar.with_suffix(output_aar.suffix + ".sha256").write_text(
        f"{digest}  {output_aar.name}\n",
        encoding="utf-8",
    )
    return selected_by_abi


def package_ycore_gpu_aar(
    source_aar: pathlib.Path,
    output_aar: pathlib.Path,
    provenance: pathlib.Path,
    notice: pathlib.Path,
    readelf: str,
) -> Mapping[str, tuple[str, ...]]:
    """Build the GPU-only companion used next to the verified MPV runtime.

    The full application already receives libc++_shared from libmpv. Keeping only the Vulkan
    executor here avoids duplicate FFmpeg/demux libraries while allowing YCore GPU, MPV and MDK
    to coexist in one package. The dependency check deliberately fails if the executor starts
    depending on anything except Android system libraries and that single companion runtime.
    """
    if not source_aar.is_file():
        raise PackagingError(f"source AAR does not exist: {source_aar}")
    if not provenance.is_file():
        raise PackagingError(f"provenance manifest does not exist: {provenance}")
    if not notice.is_file():
        raise PackagingError(f"third-party notice does not exist: {notice}")

    selected_by_abi: dict[str, tuple[str, ...]] = {}
    staged_bytes: dict[str, bytes] = {}
    with zipfile.ZipFile(source_aar, "r") as source, tempfile.TemporaryDirectory(
        prefix="ycore-gpu-aar.",
    ) as temporary:
        grouped = grouped_native_entries(source)
        for abi, entries in sorted(grouped.items()):
            archive_name = entries.get(GPU_LIBRARY)
            if archive_name is None:
                continue
            abi_dir = pathlib.Path(temporary, abi)
            abi_dir.mkdir(parents=True)
            library = abi_dir / GPU_LIBRARY
            library.write_bytes(source.read(archive_name))
            dependencies = readelf_needed(readelf, library)
            unexpected = set(dependencies) - SYSTEM_LIBRARIES - COMPANION_LIBRARIES
            if unexpected:
                raise PackagingError(
                    f"{GPU_LIBRARY} has unsupported companion dependencies: "
                    f"{', '.join(sorted(unexpected))}",
                )
            if "libvulkan.so" not in dependencies or "libandroid.so" not in dependencies:
                raise PackagingError(f"{GPU_LIBRARY} is not the Vulkan/AHardwareBuffer executor")
            selected_by_abi[abi] = (GPU_LIBRARY,)
            staged_bytes[abi] = library.read_bytes()

    if "arm64-v8a" not in selected_by_abi:
        raise PackagingError("source AAR has no arm64-v8a YCore GPU executor")

    manifest = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        'package="com.yfuse.ycore.gpucarrier" />\n'
    )
    output_aar.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = output_aar.with_name(f".{output_aar.name}.tmp")
    try:
        with zipfile.ZipFile(temporary_output, "w") as output:
            output.writestr(deterministic_info("AndroidManifest.xml"), manifest)
            output.writestr(deterministic_info("classes.jar"), empty_classes_jar())
            output.writestr(deterministic_info("R.txt"), "")
            output.writestr(deterministic_info("consumer-rules.pro"), "")
            output.writestr(
                deterministic_info("META-INF/ycore-gpu-sources.txt"),
                provenance.read_text(encoding="utf-8"),
            )
            output.writestr(
                deterministic_info("META-INF/NOTICE"),
                notice.read_text(encoding="utf-8"),
            )
            for abi, content in sorted(staged_bytes.items()):
                output.writestr(
                    deterministic_info(f"jni/{abi}/{GPU_LIBRARY}", mode=0o755),
                    content,
                )
        temporary_output.replace(output_aar)
    finally:
        temporary_output.unlink(missing_ok=True)

    digest = hashlib.sha256(output_aar.read_bytes()).hexdigest()
    output_aar.with_suffix(output_aar.suffix + ".sha256").write_text(
        f"{digest}  {output_aar.name}\n",
        encoding="utf-8",
    )
    return selected_by_abi


class SelfTest(unittest.TestCase):
    def test_parse_needed(self) -> None:
        self.assertEqual(
            parse_needed(" 0x1 (NEEDED) Shared library: [libavcodec.so]\n"),
            ("libavcodec.so",),
        )

    def test_closure_ignores_system_and_unreachable_legacy_libraries(self) -> None:
        graph = {
            SEED_LIBRARY: ("libavformat.so", "liblog.so"),
            "libavformat.so": ("libavcodec.so",),
            "libavcodec.so": ("libc.so",),
        }
        self.assertEqual(
            dependency_closure((*graph, "libmpv.so"), lambda name: graph[name]),
            ("libavcodec.so", "libavformat.so", SEED_LIBRARY),
        )

    def test_closure_rejects_legacy_dependency(self) -> None:
        with self.assertRaisesRegex(PackagingError, "forbidden legacy"):
            dependency_closure(
                (SEED_LIBRARY, "libmpv.so"),
                lambda name: ("libmpv.so",) if name == SEED_LIBRARY else (),
            )

    def test_closure_rejects_missing_non_system_dependency(self) -> None:
        with self.assertRaisesRegex(PackagingError, "unavailable libavcodec"):
            dependency_closure((SEED_LIBRARY,), lambda _: ("libavcodec.so",))

    def test_closure_includes_optional_gpu_seed(self) -> None:
        graph = {
            SEED_LIBRARY: ("libavformat.so",),
            "libavformat.so": ("libc.so",),
            "libycore_gpu.so": ("libvulkan.so",),
        }
        self.assertEqual(
            dependency_closure(graph, lambda name: graph[name], (SEED_LIBRARY, "libycore_gpu.so")),
            ("libavformat.so", SEED_LIBRARY, "libycore_gpu.so"),
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_aar", nargs="?", type=pathlib.Path)
    parser.add_argument("output_aar", nargs="?", type=pathlib.Path)
    parser.add_argument("provenance", nargs="?", type=pathlib.Path)
    parser.add_argument("--readelf", default=shutil.which("readelf") or "readelf")
    parser.add_argument(
        "--notice",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parents[1] / "NOTICE",
    )
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--gpu-only",
        action="store_true",
        help="package only libycore_gpu.so for use next to the full MPV/MDK runtime",
    )
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(SelfTest)
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1
    if args.source_aar is None or args.output_aar is None or args.provenance is None:
        parser.error("source_aar, output_aar, and provenance are required")
    try:
        package = package_ycore_gpu_aar if args.gpu_only else package_ycore_aar
        selected = package(
            args.source_aar,
            args.output_aar,
            args.provenance,
            args.notice,
            args.readelf,
        )
    except (OSError, PackagingError, zipfile.BadZipFile) as error:
        parser.exit(1, f"error: {error}\n")
    for abi, libraries in selected.items():
        print(f"[ycore-aar] {abi}: {', '.join(libraries)}")
    print(f"[ycore-aar] wrote {args.output_aar}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
