import hashlib
import io
import json
import unittest
import urllib.error

from admission import check_path


class TestConnection:
    def __init__(self, body, failures=None):
        self.body = body
        self.failures = dict(failures or {})
        self.calls = 0

    def open(self, request, timeout):
        self.calls += 1
        if self.calls in self.failures:
            raise self.failures[self.calls]
        first, last = map(int, request.get_header('Range')[6:].split('-'))
        response = io.BytesIO(self.body[first:last + 1])
        response.status = 206
        response.headers = {'Content-Range': f'bytes {first}-{last}/{len(self.body)}'}
        return response


class AdmissionTest(unittest.TestCase):
    def setUp(self):
        self.body = bytes(range(251)) * 19
        size = len(self.body)
        hashes = {k: hashlib.sha256(self.body[i:i + 1024]).hexdigest()[:16]
                  for k, i in (('start', 0), ('middle', size // 2), ('end', size - 1024))}
        row = {'size': size, 'location': 'https://example.invalid/private-capability', 'rangeHashes': hashes}
        self.items = {label: row for label in ('m-000', 'm-002', 'e-001')}

    def test_records_two_rounds_of_three_ranges_for_each_kind(self):
        receipt = check_path(self.items, TestConnection(self.body))
        self.assertTrue(receipt['remoteAdmissionPassed'] and receipt['allSamplesMatched'])
        self.assertEqual(12, len(receipt['samples']))
        self.assertEqual({'m-000','e-001'}, {r['label'] for r in receipt['samples']})
        self.assertNotIn('private-capability', json.dumps(receipt))

    def test_keeps_transient_failures_in_the_first_pass(self):
        error = urllib.error.URLError(ConnectionResetError(104, 'private-capability'))
        receipt = check_path(self.items, TestConnection(self.body, {2: error}))
        self.assertTrue(receipt['remoteAdmissionPassed'])
        self.assertFalse(receipt['allSamplesMatched'])
        self.assertEqual(104, receipt['samples'][1]['errno'])
        self.assertEqual('ConnectionResetError', receipt['samples'][1]['transportCause'])
        self.assertNotIn('private-capability', json.dumps(receipt))

    def test_denial_or_wrong_bytes_stop_even_after_a_previous_match(self):
        error = urllib.error.HTTPError('https://example.invalid/private-capability', 403, 'private-capability', {}, None)
        origin = TestConnection(self.body, {2: error})
        result = check_path(self.items, origin)
        self.assertFalse(result['remoteAdmissionPassed'])
        self.assertEqual(2, origin.calls)
        self.items['m-000']['rangeHashes']['middle'] = 'incorrect'
        origin = TestConnection(self.body)
        result = check_path(self.items, origin)
        self.assertFalse(result['remoteAdmissionPassed'])
        self.assertEqual('unexpected-response-bytes-or-range', result['samples'][-1]['stopReason'])
        self.assertEqual(2, origin.calls)
