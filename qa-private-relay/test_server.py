import http.client
import importlib.util
import io
from pathlib import Path
import threading
import time
import unittest
import ssl
import tempfile
from cryptography.hazmat.primitives import serialization

spec = importlib.util.spec_from_file_location("relay", Path(__file__).with_name("server.py"))
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)


class Response(io.BytesIO):
    status = 206

    def __init__(self, body, content_range):
        super().__init__(body)
        self.headers = {"Content-Range": content_range}


class Upstream:
    def __init__(self):
        self.requests = []
        self.wrong_range = False

    def open(self, request, timeout):
        self.requests.append(request)
        lo, hi, _ = relay.requested_range(request.get_header("Range"), 10)
        return Response(b"0123456789"[lo:hi + 1], "bytes %d-%d/10" % (lo + int(self.wrong_range), hi))


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.origin = Upstream()
        self.token = "a" * 43
        self.state = relay.RelayState({"m-001": {"location": "https://media.invalid/example", "size": 10}},
                                      self.token, 30, 100, self.origin)
        self.server = relay.BoundedHTTPServer(("127.0.0.1", 0), relay.handler_for(self.state))
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(2)

    def request(self, path=None, method="GET", headers=None, read=True):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request(method, path or "/" + self.token + "/m-001", headers=headers or {})
        response = connection.getresponse()
        try:
            return response.status, dict(response.getheaders()), response.read() if read else None
        finally:
            connection.close()

    def test_auth_and_allowlist_never_reach_upstream(self):
        for path in ("/wrong/m-001", "/" + "b" * 43 + "/m-001", "/" + self.token + "/e-002",
                     "/" + self.token + "/m-001?url=https://other.invalid", "/" + self.token + "/../m-001"):
            self.assertEqual(404, self.request(path)[0])
        self.assertEqual([], self.origin.requests)

    def test_exact_range_bytes_and_no_credential_forwarding(self):
        status, headers, body = self.request(headers={"Range": "bytes=3-6", "Authorization": "secret", "Cookie": "secret"})
        self.assertEqual((206, "bytes 3-6/10", b"3456"), (status, headers["Content-Range"], body))
        self.assertIsNone(self.origin.requests[0].get_header("Authorization"))
        self.assertIsNone(self.origin.requests[0].get_header("Cookie"))
        self.assertEqual(4, self.state.bytes_sent)

    def test_head_does_not_fetch_media(self):
        status, headers, body = self.request(method="HEAD")
        self.assertEqual((200, "10", b""), (status, headers["Content-Length"], body))
        self.assertEqual([], self.origin.requests)

    def test_expiry_and_stop_revoke_access(self):
        self.state.deadline = time.monotonic() - 1
        self.assertEqual(410, self.request()[0])
        self.state.deadline = time.monotonic() + 30
        self.assertEqual(204, self.request("/" + self.token + "/stop", method="POST")[0])
        self.assertEqual(410, self.request()[0])
        self.assertEqual([], self.origin.requests)

    def test_invalid_and_multiple_ranges_are_rejected(self):
        for value in ("bytes=20-21", "bytes=9-4", "bytes=-0", "bytes=0-2,4-5", "bytes=-"):
            self.assertEqual(416, self.request(headers={"Range": value})[0])
        self.assertEqual([], self.origin.requests)

    def test_wrong_upstream_range_is_not_forwarded(self):
        self.origin.wrong_range = True
        self.assertEqual(502, self.request(headers={"Range": "bytes=3-6"})[0])
        self.assertEqual(0, self.state.bytes_sent)

    def test_budget_is_atomic_and_limits_transfer(self):
        self.state.remaining = 4
        self.assertEqual(b"3456", self.request(headers={"Range": "bytes=3-6"})[2])
        self.assertEqual(429, self.request(headers={"Range": "bytes=0-1"})[0])
        self.assertEqual(1, len(self.origin.requests))
        self.assertEqual(4, self.state.bytes_sent)

    def test_suffix_and_open_ranges(self):
        self.assertEqual(b"89", self.request(headers={"Range": "bytes=-2"})[2])
        self.assertEqual(b"789", self.request(headers={"Range": "bytes=7-"})[2])

    def test_tls_requires_the_exact_session_certificate(self):
        spec = importlib.util.spec_from_file_location("session", Path(__file__).with_name("serve_session.py"))
        session = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(session)
        key, certificate = session.temporary_certificate("127.0.0.1", 30)
        _, wrong_certificate = session.temporary_certificate("127.0.0.1", 30)
        with tempfile.TemporaryDirectory() as directory:
            cert_file, key_file = Path(directory) / "cert.pem", Path(directory) / "key.pem"
            cert_file.write_text(certificate)
            key_file.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                   serialization.NoEncryption()))
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(cert_file, key_file)
            server = relay.BoundedHTTPServer(("127.0.0.1", 0), relay.handler_for(self.state), tls)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                for cert, accepted in ((certificate, True), (wrong_certificate, False)):
                    context = ssl.create_default_context(cadata=cert)
                    connection = http.client.HTTPSConnection("127.0.0.1", server.server_port, context=context, timeout=3)
                    try:
                        if accepted:
                            connection.request("GET", "/" + self.token + "/m-001", headers={"Range": "bytes=2-4"})
                            response = connection.getresponse()
                            self.assertEqual((206, b"234"), (response.status, response.read()))
                        else:
                            with self.assertRaises(ssl.SSLCertVerificationError):
                                connection.request("GET", "/" + self.token + "/m-001")
                    finally:
                        connection.close()
            finally:
                server.shutdown()
                server.server_close()
                worker.join(2)


if __name__ == "__main__":
    unittest.main()
