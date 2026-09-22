"""Contract checks for current-JNI builds with byte-identical published shared dependencies."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "build-current-ycore-native.sh"
BASH = os.environ.get("YCORE_TEST_BASH") or shutil.which("bash")


@unittest.skipUnless(BASH, "bash is required")
class CurrentYCoreBuildTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="ycore-ci-contract-")
        self.root = Path(self.directory.name)
        scripts = self.root / "scripts"
        (scripts / "native").mkdir(parents=True)
        (scripts / SCRIPT.name).write_text(SCRIPT.read_text(), encoding="utf-8")
        for name in ("build-yfuse-mpv-bluray.sh", "build-yfuse-mpv-dolby.sh", "yfuse-mpv-sources.txt"):
            (scripts / name).write_text("pinned dependency input\n", encoding="utf-8")
        (scripts / "native/patch_yfuse_test.py").write_text("pinned patch\n", encoding="utf-8")
        (scripts / "native/ycore_demux_jni.cpp").write_text("source-v1", encoding="utf-8")
        libs = self.root / "composeApp/libs"
        libs.mkdir(parents=True)
        self.carrier = libs / "libmpv-release.aar"
        with zipfile.ZipFile(self.carrier, "w") as archive:
            archive.writestr("jni/arm64-v8a/libavformat.so", b"published FFmpeg bytes")
            archive.writestr("jni/arm64-v8a/libc++_shared.so", b"published C++ bytes")
            archive.writestr("jni/arm64-v8a/libycore_demux.so", b"obsolete JNI")
        self.original_hash = hashlib.sha256(self.carrier.read_bytes()).hexdigest()
        (scripts / "engine-checksums.sha256").write_text(self.original_hash + "  libmpv-release.aar\n", encoding="utf-8")
        self.manifest = "ffmpeg=" + "a" * 40 + "\nlibbluray=bluray-pin\nlibudfread=udf-pin\nlibass=0.17.4\n"
        (libs / "libmpv-release.sources.txt").write_text(self.manifest, encoding="utf-8")
        self.write_stage("build-ycore-native.sh", """
prefix = root / '.native-build/yfuse-mpv/source/buildscripts/prefix/arm64-v8a'
(prefix / 'lib').mkdir(parents=True)
(prefix / 'include/libavformat').mkdir(parents=True)
(prefix / 'lib/libass.a').write_bytes(b'pinned static ASS')
(prefix / 'lib/libavformat.so').write_bytes(b'rebuilt but different FFmpeg bytes')
(prefix / 'include/libavformat/avformat.h').write_text('pinned header')
(root / '.native-build/yfuse-mpv/source/buildscripts/sdk/android-sdk-linux/ndk/29.0.14206865').mkdir(parents=True)
(root / '.native-build/artifacts').mkdir(parents=True)
(root / '.native-build/artifacts/NATIVE-SOURCES.txt').write_text((root / 'composeApp/libs/libmpv-release.sources.txt').read_text())
with (root / 'stages.txt').open('a') as output: output.write('bootstrap\\n')
""")
        self.write_stage("build-ycore-demux.sh", """
import zipfile
prefix = root / '.native-build/yfuse-mpv/source/buildscripts/prefix/arm64-v8a/lib'
assert (prefix / 'libavformat.so').read_bytes() == b'published FFmpeg bytes'
assert (prefix / 'libc++_shared.so').read_bytes() == b'published C++ bytes'
assert not (prefix / 'libycore_demux.so').exists()
with zipfile.ZipFile(root / '.native-build/artifacts/libmpv-yfuse-bluray.aar') as archive:
    assert archive.read('jni/arm64-v8a/libavformat.so') == b'published FFmpeg bytes'
with (root / 'stages.txt').open('a') as output:
    output.write((root / 'scripts/native/ycore_demux_jni.cpp').read_text() + '\\n')
""")
        self.write_stage("install-ycore-native.sh", "with (root / 'stages.txt').open('a') as output: output.write('install\\n')")
        binaries = self.root / "bin"
        binaries.mkdir()
        python_wrapper = binaries / "python3"
        python_wrapper.write_text("#!/usr/bin/env bash\nexec '" + sys.executable.replace("\\", "/").replace("'", "'\\''") + "' \"$@\"\n", encoding="utf-8")
        python_wrapper.chmod(0o755)
        self.environment = dict(os.environ, YCORE_TEST_ROOT=str(self.root))
        self.environment["PATH"] = str(binaries) + os.pathsep + os.environ.get("PATH", "")

    def tearDown(self):
        self.directory.cleanup()

    def write_stage(self, name, body):
        text = "#!/usr/bin/env bash\nset -euo pipefail\npython3 - <<'PY'\nimport os\nfrom pathlib import Path\nroot = Path(os.environ['YCORE_TEST_ROOT'])\n" + body + "\nPY\n"
        (self.root / "scripts" / name).write_text(text, encoding="utf-8")

    def run_build(self):
        return subprocess.run([BASH, str(self.root / "scripts" / SCRIPT.name)], cwd=self.root,
                              env=self.environment, text=True, capture_output=True, timeout=30)

    def test_warm_dependency_cache_still_compiles_current_sources_and_preserves_carrier(self):
        first = self.run_build()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        (self.root / "scripts/native/ycore_demux_jni.cpp").write_text("source-v2", encoding="utf-8")
        second = self.run_build()
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual(["bootstrap", "source-v1", "install", "source-v2", "install"],
                         (self.root / "stages.txt").read_text().splitlines())
        self.assertEqual(self.original_hash, hashlib.sha256(self.carrier.read_bytes()).hexdigest())

    def test_modified_carrier_is_rejected_before_any_native_stage(self):
        self.carrier.write_bytes(self.carrier.read_bytes() + b"changed")
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not the pinned release", result.stderr)
        self.assertFalse((self.root / "stages.txt").exists())

    def test_cached_headers_from_another_ffmpeg_revision_are_rejected(self):
        result = self.run_build()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        (self.root / ".native-build/ycore-dependencies.sources.txt").write_text(
            self.manifest.replace("a" * 40, "b" * 40), encoding="utf-8")
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cached ffmpeg differs", result.stderr)
        self.assertEqual(["bootstrap", "source-v1", "install"], (self.root / "stages.txt").read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
