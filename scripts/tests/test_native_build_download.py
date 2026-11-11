import hashlib
import importlib.util
import http.client
import io
import json
import os
import ssl
import tempfile
import unittest
import urllib.error
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
            response.headers = {}
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
            response.headers = {}
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
            response.headers = {}
            response.geturl.return_value = "https://www.lua.org/ftp/lua-5.2.4.tar.gz"
            response.read.side_effect = [b"oversized", b""]
            opener = mock.Mock()
            opener.open.return_value = response
            with mock.patch.object(download, "MAX_BYTES", 1):
                with self.assertRaisesRegex(ValueError, "download budget"):
                    download.download("http://www.lua.org/ftp/lua-5.2.4.tar.gz", str(output), opener)
            self.assertFalse(output.exists())


class NativeBuildDownloadRetryTest(unittest.TestCase):
    url = "http://www.lua.org/ftp/lua-5.2.4.tar.gz"
    resolved = "https://www.lua.org/ftp/lua-5.2.4.tar.gz"
    payload = b"valid"

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name) / "archive"
        self.output.write_bytes(b"previous valid input")
        self.ledger = Path(temporary.name) / "receipts.jsonl"
        self.opener = mock.Mock()
        self.sleep = self.enterContext(mock.patch.object(download.time, "sleep"))
        self.stdout = self.enterContext(mock.patch.object(download.sys, "stdout"))
        self.stdout.buffer = io.BytesIO()
        self.stderr = self.enterContext(mock.patch.object(download.sys, "stderr", io.StringIO()))
        self.enterContext(mock.patch.dict(os.environ, {"YCORE_DOWNLOAD_RECEIPTS": str(self.ledger)}))
        self.enterContext(mock.patch.dict(download.SOURCES, {
            self.url: (self.resolved, hashlib.sha256(self.payload).hexdigest())}))

    def response(self, chunks=None, length=None, url=None):
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.geturl.return_value = url or self.resolved
        response.headers = {} if length is None else {"Content-Length": str(length)}
        response.read.side_effect = [self.payload, b""] if chunks is None else chunks
        return response

    def wire_response(self, body, length):
        socket = mock.Mock()
        socket.makefile.return_value = io.BytesIO(
            f"HTTP/1.1 200 OK\r\nContent-Length: {length}\r\n\r\n".encode() + body)
        response = http.client.HTTPResponse(socket)
        response.begin()
        response.url = self.resolved
        return response

    def fetch(self, output=None):
        download.download(self.url, str(self.output) if output is None else output, self.opener)

    def assert_no_output_or_receipt(self):
        self.assertEqual(b"previous valid input", self.output.read_bytes())
        self.assertEqual(b"", self.stdout.buffer.getvalue())
        self.assertFalse(self.ledger.exists())

    def assert_one_receipt(self):
        records = [json.loads(line) for line in self.ledger.read_text().splitlines()]
        self.assertEqual([{"url": self.resolved, "sha256": hashlib.sha256(self.payload).hexdigest(),
                           "bytes": len(self.payload), "checksumPinned": True}], records)

    def test_only_explicit_transient_network_errors_are_retryable(self):
        for error in (TimeoutError(), ConnectionResetError(), ConnectionAbortedError(),
                      BrokenPipeError(), http.client.RemoteDisconnected(), ssl.SSLEOFError(),
                      http.client.IncompleteRead(b"partial", 5)):
            for wrapped in (error, urllib.error.URLError(error)):
                with self.subTest(error=wrapped):
                    self.assertTrue(download.transient_network_error(wrapped))
        for status in (429, 500, 502, 503, 504):
            with self.subTest(status=status):
                error = urllib.error.HTTPError(self.resolved, status, "transient", {}, None)
                self.assertTrue(download.transient_network_error(error))

    def test_permanent_errors_are_not_retryable(self):
        errors = [ssl.SSLCertVerificationError(1, "certificate verify failed"),
                  ssl.SSLError(1, "TLS configuration error"),
                  urllib.error.URLError("timed out"), ValueError("policy violation"),
                  OSError("disk full"), PermissionError("permission denied"),
                  http.client.BadStatusLine("invalid")]
        errors.extend(urllib.error.HTTPError(self.resolved, status, "permanent", {}, None)
                      for status in (400, 401, 403, 404, 408, 501, 505))
        for error in errors:
            for wrapped in (error, urllib.error.URLError(error)):
                with self.subTest(error=wrapped):
                    self.assertFalse(download.transient_network_error(wrapped))

    def test_tls_eof_during_open_retries_exact_input_and_emits_once(self):
        error = urllib.error.URLError(ssl.SSLEOFError(8, "UNEXPECTED_EOF_WHILE_READING"))
        self.opener.open.side_effect = [error, self.response(length=len(self.payload))]
        self.fetch("-")
        self.assertEqual([mock.call(self.resolved, timeout=90)] * 2, self.opener.open.call_args_list)
        self.sleep.assert_called_once_with(1)
        self.assertEqual(self.payload, self.stdout.buffer.getvalue())
        self.assert_one_receipt()
        self.assertEqual(1, self.stderr.getvalue().count("sha256="))

    def test_partial_transfers_reset_bytes_checksum_and_budget(self):
        first = self.response([b"p" * 40, ssl.SSLEOFError()])
        second = self.response([b"partial", ConnectionResetError()])
        self.opener.open.side_effect = [first, second, self.response()]
        self.sleep.side_effect = lambda delay: self.assert_no_output_or_receipt()
        with mock.patch.object(download, "MAX_BYTES", 40):
            self.fetch()
        self.assertEqual(self.payload, self.output.read_bytes())
        self.assertEqual([mock.call(self.resolved, timeout=90)] * 3, self.opener.open.call_args_list)
        self.assertEqual([mock.call(1), mock.call(2)], self.sleep.call_args_list)
        first.__exit__.assert_called_once()
        second.__exit__.assert_called_once()
        self.assert_one_receipt()

    def test_transient_http_error_is_closed_before_retry(self):
        body = io.BytesIO(b"temporarily unavailable")
        error = urllib.error.HTTPError(self.resolved, 503, "unavailable", {}, body)
        self.opener.open.side_effect = [error, self.response()]
        self.sleep.side_effect = lambda delay: self.assertTrue(body.closed)
        self.fetch()
        self.assertEqual(2, self.opener.open.call_count)
        self.assert_one_receipt()

    def test_repeated_timeouts_fail_after_three_attempts_without_any_output(self):
        self.opener.open.side_effect = urllib.error.URLError(TimeoutError("timed out"))
        with self.assertRaises(urllib.error.URLError):
            self.fetch("-")
        self.assertEqual(3, self.opener.open.call_count)
        self.assertEqual([mock.call(1), mock.call(2)], self.sleep.call_args_list)
        self.assert_no_output_or_receipt()

    def test_content_length_truncation_retries_before_accepting_unpinned_input(self):
        self.opener.open.side_effect = [self.wire_response(b"part", 5),
                                      self.response(length=5)]
        with mock.patch.dict(download.SOURCES, {self.url: (self.resolved, None)}):
            self.fetch()
        self.assertEqual(2, self.opener.open.call_count)
        self.assertEqual(self.payload, self.output.read_bytes())
        records = [json.loads(line) for line in self.ledger.read_text().splitlines()]
        self.assertEqual(1, len(records))
        self.assertEqual(len(self.payload), records[0]["bytes"])
        self.assertFalse(records[0]["checksumPinned"])

    def test_reproducible_truncation_fails_closed_in_main(self):
        self.opener.open.side_effect = [self.wire_response(b"part", 5) for _ in range(3)]
        with mock.patch.object(download.urllib.request, "build_opener", return_value=self.opener), \
                mock.patch.dict(download.SOURCES, {self.url: (self.resolved, None)}):
            self.assertEqual(1, download.main([self.url, "-O", "-"]))
        self.assertEqual(3, self.opener.open.call_count)
        self.assertEqual([mock.call(1), mock.call(2)], self.sleep.call_args_list)
        self.assert_no_output_or_receipt()

    def test_incomplete_chunked_read_retries_entire_transfer(self):
        self.opener.open.side_effect = [self.response([b"part", http.client.IncompleteRead(b"x", 5)]),
                                      self.response()]
        self.fetch()
        self.assertEqual(2, self.opener.open.call_count)
        self.assertEqual(self.payload, self.output.read_bytes())
        self.assert_one_receipt()

    def test_certificate_failure_and_404_fail_immediately(self):
        errors = [ssl.SSLCertVerificationError(1, "certificate verify failed"),
                  urllib.error.URLError(ssl.SSLCertVerificationError(1, "certificate verify failed")),
                  urllib.error.HTTPError(self.resolved, 404, "not found", {}, None)]
        for error in errors:
            with self.subTest(error=error):
                self.opener.open.reset_mock()
                self.opener.open.side_effect = error
                with self.assertRaises(type(error)):
                    self.fetch()
                self.opener.open.assert_called_once_with(self.resolved, timeout=90)
                self.sleep.assert_not_called()
                self.assert_no_output_or_receipt()

    def test_corrupt_or_empty_input_is_never_retried(self):
        for chunks in ([b"corrupt", b""], [b""]):
            with self.subTest(chunks=chunks):
                self.opener.open.reset_mock()
                self.opener.open.return_value = self.response(chunks)
                with self.assertRaisesRegex(ValueError, "checksum mismatch or empty"):
                    self.fetch()
                self.opener.open.assert_called_once()
                self.sleep.assert_not_called()
                self.assert_no_output_or_receipt()

    def test_retry_does_not_hide_checksum_failure(self):
        self.opener.open.side_effect = [TimeoutError(), self.response([b"corrupt", b""])]
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.fetch()
        self.assertEqual(2, self.opener.open.call_count)
        self.sleep.assert_called_once_with(1)
        self.assert_no_output_or_receipt()

    def test_redirect_or_response_downgrade_is_never_retried(self):
        responses = [ValueError("Native input redirect must remain HTTPS"),
                     self.response(url="http://www.lua.org/ftp/lua-5.2.4.tar.gz")]
        for response in responses:
            with self.subTest(response=response):
                self.opener.open.reset_mock()
                self.opener.open.side_effect = [response]
                with self.assertRaisesRegex(ValueError, "HTTPS"):
                    self.fetch()
                self.opener.open.assert_called_once()
                self.sleep.assert_not_called()
                self.assert_no_output_or_receipt()

    def test_unknown_url_is_rejected_before_network(self):
        with self.assertRaisesRegex(ValueError, "allowlist"):
            download.download("https://example.test/archive", str(self.output), self.opener)
        self.opener.open.assert_not_called()
        self.sleep.assert_not_called()
        self.assert_no_output_or_receipt()

    def test_byte_cap_and_invalid_lengths_are_never_retried(self):
        responses = [self.response([b"oversized", b""]), self.response(length=6),
                     self.response(length=-1), self.response(length="invalid"), self.response(length=3)]
        for response in responses:
            with self.subTest(headers=response.headers):
                self.opener.open.reset_mock()
                self.opener.open.return_value = response
                with mock.patch.object(download, "MAX_BYTES", 5):
                    with self.assertRaises(ValueError):
                        self.fetch()
                self.opener.open.assert_called_once()
                self.sleep.assert_not_called()
                self.assert_no_output_or_receipt()

    def test_staged_bytes_remain_private_until_complete(self):
        def chunks():
            yield self.payload
            self.assert_no_output_or_receipt()
            yield b""
        self.opener.open.return_value = self.response(chunks())
        self.fetch("-")
        self.assertEqual(self.payload, self.stdout.buffer.getvalue())
        self.assert_one_receipt()

    def test_retry_exhaustion_preserves_existing_receipt_and_output(self):
        self.ledger.write_text("previous receipt\n")
        self.opener.open.side_effect = TimeoutError()
        with self.assertRaises(TimeoutError):
            self.fetch()
        self.assertEqual(3, self.opener.open.call_count)
        self.assertEqual(b"previous valid input", self.output.read_bytes())
        self.assertEqual("previous receipt\n", self.ledger.read_text())

    def test_local_receipt_failure_is_never_retried(self):
        self.opener.open.return_value = self.response()
        with mock.patch.object(Path, "open", side_effect=PermissionError("cannot write receipt")):
            with self.assertRaises(PermissionError):
                self.fetch()
        self.opener.open.assert_called_once()
        self.sleep.assert_not_called()
        self.assert_no_output_or_receipt()

    def test_local_output_failure_is_never_retried_or_logged_twice(self):
        self.opener.open.return_value = self.response()
        self.stdout.buffer = mock.Mock()
        self.stdout.buffer.write.side_effect = BrokenPipeError("consumer closed")
        with self.assertRaises(BrokenPipeError):
            self.fetch("-")
        self.opener.open.assert_called_once()
        self.sleep.assert_not_called()
        self.assert_one_receipt()


if __name__ == "__main__":
    unittest.main()
