"""Local fault-injection checks; no account or network access."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

transport = types.ModuleType('transport')
transport.credentials = lambda: (_ for _ in ()).throw(AssertionError('No credentials in host tests'))
transport.TEMP = Path(tempfile.gettempdir())
transport.RUN = 'host-only'
with patch.dict(sys.modules, {'transport': transport}):
    spec = importlib.util.spec_from_file_location('account_cleanup', Path(__file__).with_name('account.py'))
    account = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(account)


class CleanupTest(unittest.TestCase):
    def test_one_failed_revocation_does_not_skip_second_device_or_logout(self):
        coordinator = account.Coordinator.__new__(account.Coordinator)
        first, second = 'a'*32, 'b'*32
        coordinator.state = {'baseline': ['unrelated'], 'created': {'phone': first, 'tv': second}}
        calls = []
        def api(path, body=None, method=None):
            calls.append(path)
            if path.endswith(first): raise HTTPError(path, 400, 'missing', {}, None)
            return {'sessions': [{'id': 'unrelated'}]}
        coordinator.api = api
        with patch.object(Path, 'write_text'), self.assertRaises(RuntimeError):
            coordinator.cleanup()
        self.assertEqual(calls, ['/api/v1/account/sessions/'+first,
            '/api/v1/account/sessions/'+second, '/api/v1/account/sessions', '/api/v1/auth/logout'])

    def test_verification_failure_still_logs_out_coordinator(self):
        coordinator = account.Coordinator.__new__(account.Coordinator)
        coordinator.state = {'baseline': [], 'created': {}}
        calls = []
        def api(path, body=None, method=None):
            calls.append(path)
            if path.endswith('/sessions'): raise TimeoutError()
        coordinator.api = api
        with patch.object(Path, 'write_text'), self.assertRaises(RuntimeError):
            coordinator.cleanup()
        self.assertEqual(calls[-1], '/api/v1/auth/logout')


if __name__ == '__main__': unittest.main()
