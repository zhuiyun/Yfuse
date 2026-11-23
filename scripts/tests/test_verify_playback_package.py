import importlib.util
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "verify-playback-package.py"
spec = importlib.util.spec_from_file_location("playback_package", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def elf_fixture(alignment=16384, dependency="libc.so"):
    strings = dependency.encode("ascii") + b"\0"
    data = bytearray(272 + len(strings))
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 4, 0, 0, 0, len(data), len(data), alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 2, 4, 192, 192, 0, 64, 64, 8)
    for index, entry in enumerate([(1, 0), (5, 272), (10, len(strings)), (0, 0)]):
        struct.pack_into("<qQ", data, 192 + 16 * index, *entry)
    data[272:] = strings
    return bytes(data)


def dex_fixture(name="com.yfuse.Player"):
    # A structural class-table fixture only; the separate ART verifier gate checks executable DEX.
    descriptor = ("L" + name.replace(".", "/") + ";").encode("ascii")
    data = bytearray(153 + len(descriptor) + 1)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<I", data, 32, len(data))
    struct.pack_into("<I", data, 40, 0x12345678)
    struct.pack_into("<II", data, 56, 1, 112)
    struct.pack_into("<II", data, 64, 1, 116)
    struct.pack_into("<II", data, 96, 1, 120)
    struct.pack_into("<I", data, 112, 152)
    data[152] = len(descriptor)
    data[153:153 + len(descriptor)] = descriptor
    return bytes(data)


class PlaybackPackageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.apk = root / "application.apk"
        self.ycore = root / "ycore-native.aar"
        self.manifest = root / "runtime.json"
        self.mapping = root / "mapping.txt"
        self.native = elf_fixture()
        with zipfile.ZipFile(self.ycore, "w") as archive:
            for name in checker.YCORE_LIBRARIES:
                archive.writestr(f"jni/arm64-v8a/{name}", self.native)
        self.ycore.with_suffix(".aar.sha256").write_text(checker.sha256(self.ycore.read_bytes()) + "  ycore-native.aar")
        self.entries = {f"lib/arm64-v8a/{name}": self.native for name in checker.YCORE_LIBRARIES}
        self.entries["classes.dex"] = dex_fixture()
        records = []
        for asset in sorted(checker.SEN_ASSETS):
            content = self.native if asset.endswith(".so") else b"original SenPlayer fixture"
            self.entries["assets/senplayer/" + asset] = content
            records.append({"asset": asset, "bytes": len(content), "sha256": checker.sha256(content)})
        manifest = json.dumps({"abi": "arm64-v8a", "files": records})
        self.manifest.write_text(manifest)
        self.entries["assets/senplayer/runtime.json"] = manifest.encode()

    def verify(self, mapping=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, content in self.entries.items():
                archive.writestr(name, content)
        return checker.verify(self.apk, self.ycore, self.manifest, mapping)

    def test_exact_two_engine_fixture_passes(self):
        result = self.verify()
        self.assertEqual(5, result["senPlayerOriginalAssets"])
        self.assertEqual(1, result["applicationClassesChecked"])
        self.assertEqual("ycore-senplayer", result["profile"])

    def test_mdk_mpv_and_vlc_native_libraries_are_rejected(self):
        for name in ["libmdk.so", "libyfuse-mdk-jni.so", "libmpv.so", "libplayer.so", "libvlc.so"]:
            with self.subTest(name=name):
                entry = "lib/arm64-v8a/" + name
                self.entries[entry] = self.native
                with self.assertRaisesRegex(ValueError, "Unreviewed or removed"):
                    self.verify()
                del self.entries[entry]

    def test_removed_application_runtime_is_rejected(self):
        for name in ["com.google.android.exoplayer2.ExoPlayer", "androidx.media3.exoplayer.ExoPlayer",
                     "dev.jdtech.mpv.MPVLib", "com.mediadevkit.sdk.MDKPlayer"]:
            with self.subTest(name=name):
                self.entries["classes.dex"] = dex_fixture(name)
                with self.assertRaisesRegex(ValueError, "removed playback runtime"):
                    self.verify()

    def test_obfuscated_merged_runtime_is_rejected_using_every_mapping_origin(self):
        self.entries["classes.dex"] = dex_fixture("a")
        self.mapping.write_text("androidx.media3.exoplayer.ExoPlayer -> a:\ncom.yfuse.Safe -> a:\n")
        with self.assertRaisesRegex(ValueError, "removed playback runtime"):
            self.verify(self.mapping)

    def test_shared_media3_datasource_is_allowed(self):
        self.entries["classes.dex"] = dex_fixture("androidx.media3.datasource.cache.SimpleCache")
        self.assertEqual(1, self.verify()["applicationClassesChecked"])

    def test_tenth_dex_is_also_checked(self):
        self.entries["classes10.dex"] = dex_fixture("org.videolan.libvlc.LibVLC")
        with self.assertRaisesRegex(ValueError, "removed playback runtime"):
            self.verify()

    def test_missing_ycore_gpu_is_rejected(self):
        del self.entries["lib/arm64-v8a/libycore_gpu.so"]
        with self.assertRaisesRegex(ValueError, "Missing YCore"):
            self.verify()

    def test_changed_ycore_binary_is_rejected(self):
        self.entries["lib/arm64-v8a/libycore_demux.so"] = self.native + b"changed"
        with self.assertRaisesRegex(ValueError, "differs from verified input"):
            self.verify()

    def test_changed_original_sen_asset_is_rejected(self):
        self.entries["assets/senplayer/classes.dex"] += b"changed"
        with self.assertRaisesRegex(ValueError, "Original SenPlayer asset changed"):
            self.verify()

    def test_missing_original_sen_asset_is_rejected(self):
        del self.entries["assets/senplayer/license.json"]
        with self.assertRaisesRegex(ValueError, "missing or unexpected SenPlayer"):
            self.verify()

    def test_misaligned_support_native_is_rejected(self):
        self.entries["lib/arm64-v8a/libquickjs.so"] = elf_fixture(alignment=4096)
        with self.assertRaisesRegex(ValueError, "below 16 KiB"):
            self.verify()

    def test_unresolved_native_dependency_is_rejected(self):
        self.entries["lib/arm64-v8a/libquickjs.so"] = elf_fixture(dependency="libmpv.so")
        with self.assertRaisesRegex(ValueError, "Unresolved host native"):
            self.verify()

    def test_unsupported_abi_is_rejected(self):
        self.entries["lib/armeabi-v7a/libycore_demux.so"] = self.native
        with self.assertRaisesRegex(ValueError, "Unsupported native ABI"):
            self.verify()

    def test_empty_mapping_cannot_claim_obfuscated_class_validation(self):
        self.mapping.write_text("# no class origins\n")
        with self.assertRaisesRegex(ValueError, "no class entries"):
            self.verify(self.mapping)


if __name__ == "__main__":
    unittest.main()
