import http.client
from pathlib import Path
import tempfile
import threading
import time
import unittest

from fixture_origin import FixtureOrigin


class FixtureRequestsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.contents = bytes(range(256)) * 256
        self.path = Path(self.temp.name) / 'fixture.mp4'
        self.path.write_bytes(self.contents)

    def serve(self, paced=False):
        server = FixtureOrigin(self.path, {'before': 32768, 'paceAfter': 8192}, paced=bool(paced), port=0)
        threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .02}, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server

    def request(self, server, method, path='/film.mp4', headers=None, read=None):
        connection = http.client.HTTPConnection(*server.server_address, timeout=3)
        try:
            connection.request(method, path, headers=headers or {})
            response = connection.getresponse()
            return response.status, response.read(read)
        finally:
            connection.close()

    def test_stall_and_rejected_suffix_keep_the_request_identity_and_delivered_bytes(self):
        server = self.serve()
        self.assertEqual((200, self.contents[:32768]), self.request(server, 'GET', read=32768))
        self.assertEqual(503, self.request(server, 'GET', headers={'Range': 'bytes=32768-65535',
                          'If-Range': '"qa-rail-preview-v1"'})[0])
        self.assertEqual(200, self.request(server, 'HEAD')[0])
        self.assertEqual(416, self.request(server, 'HEAD', headers={'Range': 'bytes=66000-'})[0])
        self.assertEqual(404, self.request(server, 'GET', '/thumb.jpg')[0])
        requests = [e for e in server.events() if e['event'] == 'request']
        self.assertEqual([200, 503, 200, 416, 404], [e['status'] for e in requests])
        self.assertEqual(('bytes', 32768, 'fixture'), tuple(requests[1][k] for k in ('rangeType', 'requestedStart', 'ifRange')))
        self.assertTrue(any(e['event'] == 'body-stalled' and e['id'] == 1 and e['offset'] == 32768 for e in server.events()))
        self.assertEqual({'range': 0, 'image': 1, 'GET': 3, 'HEAD': 2, 'bytes': 32768}, server.counters())
        self.assertEqual([[0, 32767]], server.spans())

    def test_paced_media_preserves_the_bytes_and_withholds_the_same_cold_interval(self):
        server = self.serve(paced=True)
        start = time.monotonic()
        self.assertEqual((206, self.contents[8192:12288]), self.request(server, 'GET', headers={'Range': 'bytes=8192-12287'}))
        self.assertGreaterEqual(time.monotonic() - start, .35)
        self.assertEqual(503, self.request(server, 'GET', headers={'Range': 'bytes=32768-'})[0])
        self.assertEqual([[8192, 12287]], server.spans())
        self.assertEqual({'GET': 2, 'HEAD': 0, 'range': 1, 'image': 0, 'bytes': 4096}, server.counters())


if __name__ == '__main__':
    unittest.main()
