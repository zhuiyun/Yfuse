import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location("native_download", Path(__file__).parents[1] / "native-build-download.py")
download = importlib.util.module_from_spec(spec)
spec.loader.exec_module(download)


class NativeBuildDownloadTest(unittest.TestCase):
    def test_gas_master_is_replaced_before_network(self):
        url, digest = download.source_for("https://github.com/FFmpeg/gas-preprocessor/raw/master/gas-preprocessor.pl")
        self.assertIn(download.GAS_COMMIT, url)
        self.assertNotIn("master", url)
        self.assertEqual(download.GAS_SHA256, digest)

    def test_lua_http_is_upgraded_and_checksum_pinned(self):
        url, digest = download.source_for("http://www.lua.org/ftp/lua-5.2.4.tar.gz")
        self.assertEqual("https://www.lua.org/ftp/lua-5.2.4.tar.gz", url)
        self.assertEqual(download.LUA_SHA256, digest)

    def test_unreviewed_url_and_https_downgrade_are_rejected(self):
        with self.assertRaises(ValueError):
            download.source_for("https://example.test/native.tar.gz")
        with self.assertRaisesRegex(ValueError, "remain HTTPS"):
            download.HttpsRedirects().redirect_request(None, None, 302, "", {}, "http://www.lua.org/ftp/lua-5.2.4.tar.gz")

    def test_invalid_archive_never_replaces_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "archive"
            output.write_bytes(b"previous valid input")
            response = mock.MagicMock()
            response.__enter__.return_value = response
            response.geturl.return_value = "https://www.lua.org/ftp/lua-5.2.4.tar.gz"
            response.read.side_effect = [b"corrupt", b""]
            opener = mock.Mock()
            opener.open.return_value = response
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                download.download("http://www.lua.org/ftp/lua-5.2.4.tar.gz", str(output), opener)
            self.assertEqual(b"previous valid input", output.read_bytes())
            self.assertTrue(opener.open.call_args.args[0].startswith("https://"))

    def test_verified_bytes_are_written_after_complete_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "archive"
            response = mock.MagicMock()
            response.__enter__.return_value = response
            response.geturl.return_value = "https://fixture.test/input"
            response.read.side_effect = [b"valid", b""]
            opener = mock.Mock()
            opener.open.return_value = response
            with mock.patch.dict(download.SOURCES, {"fixture": ("https://fixture.test/input", hashlib.sha256(b"valid").hexdigest())}):
                download.download("fixture", str(output), opener)
            self.assertEqual(b"valid", output.read_bytes())

    def test_upstream_wget_argument_forms(self):
        sdk = "https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
        gas = "https://github.com/FFmpeg/gas-preprocessor/raw/master/gas-preprocessor.pl"
        lua = "http://www.lua.org/ftp/lua-5.2.4.tar.gz"
        with mock.patch.object(download, "download") as fetch:
            self.assertEqual(0, download.main([sdk]))
            fetch.assert_called_with(sdk, "commandlinetools-linux-14742923_latest.zip")
            self.assertEqual(0, download.main([gas, "-O", "bin/gas-preprocessor.pl"]))
            fetch.assert_called_with(gas, "bin/gas-preprocessor.pl")
            self.assertEqual(0, download.main([lua, "-O", "-"]))
            fetch.assert_called_with(lua, "-")

    def test_download_budget_rejects_before_writing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "archive"
            response = mock.MagicMock()
            response.__enter__.return_value = response
            response.geturl.return_value = "https://www.lua.org/ftp/lua-5.2.4.tar.gz"
            response.read.side_effect = [b"oversized", b""]
            opener = mock.Mock()
            opener.open.return_value = response
            with mock.patch.object(download, "MAX_BYTES", 1):
                with self.assertRaisesRegex(ValueError, "download budget"):
                    download.download("http://www.lua.org/ftp/lua-5.2.4.tar.gz", str(output), opener)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
