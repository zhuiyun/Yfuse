"""Environment failures are actionable and are kept separate from functional checks."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('validation_env', ROOT / 'scripts/check-validation-env.py')
ENV = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ENV)


class ValidationEnvironmentTest(unittest.TestCase):
    def test_missing_tool_reports_a_remedy(self):
        with patch.object(ENV.shutil, 'which', return_value=None):
            item = ENV.check('jq')
        self.assertEqual('blocked', item['status'])
        self.assertIn('official jq', item['remedy'])

    def test_nonworking_executable_is_not_reported_as_present(self):
        with patch.object(ENV.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'broken')):
            item = ENV.version_probe('jq', ['--version'], 'install jq')
        self.assertEqual('blocked', item['status'])

    def test_probe_timeout_is_an_environment_block(self):
        with patch.object(ENV.subprocess, 'run', side_effect=subprocess.TimeoutExpired('jq', 10)):
            item = ENV.version_probe('jq', ['--version'], 'install jq')
        self.assertEqual('blocked', item['status'])

    def test_missing_python_dependency_has_a_workspace_install_command(self):
        with patch.object(ENV.importlib.metadata, 'version', side_effect=ENV.importlib.metadata.PackageNotFoundError):
            item = ENV.check('pyyaml')
        self.assertEqual('blocked', item['status'])
        self.assertIn('requirements-validation.txt', item['remedy'])

    def test_stdlib_directory_presence_does_not_claim_compilation(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(ENV.os.environ, {'CANGJIE_STDX_PATH': temp}):
            item = ENV.check('stdx')
        self.assertEqual('passed', item['status'])
        self.assertIn('actual packages are checked by cjc', item['reason'])

    def test_project_minor_api_sdk_directory_is_recognized(self):
        with tempfile.TemporaryDirectory() as temp:
            platform = Path(temp) / 'platforms' / 'android-37.0'
            platform.mkdir(parents=True)
            (platform / 'android.jar').touch()
            with patch.dict(ENV.os.environ, {'ANDROID_HOME': temp}):
                item = ENV.check('android-sdk')
        self.assertEqual('passed', item['status'])

    def test_multiple_profiles_deduplicate_shared_dependencies(self):
        with tempfile.TemporaryDirectory() as temp:
            report_path = Path(temp) / 'env.json'
            with patch.object(ENV, 'check', return_value={'status': 'passed', 'version': 'test'}) as probe, \
                 contextlib.redirect_stdout(io.StringIO()):
                code = ENV.main(['--profile', 'scripts', 'harmony-source', '--report', str(report_path)])
            report = json.loads(report_path.read_text())
        self.assertEqual(0, code)
        self.assertEqual(1, sum(call.args == ('python',) for call in probe.call_args_list))
        self.assertEqual('passed', report['status'])

    def test_failed_precheck_exits_two_and_writes_actual_result(self):
        with tempfile.TemporaryDirectory() as temp:
            report_path = Path(temp) / 'env.json'
            with patch.object(ENV, 'check', return_value={'status': 'blocked', 'reason': 'missing', 'remedy': 'install'}), \
                 contextlib.redirect_stdout(io.StringIO()):
                code = ENV.main(['--report', str(report_path)])
            report = json.loads(report_path.read_text())
        self.assertEqual(2, code)
        self.assertEqual(code, report['exitCode'])
        self.assertEqual('blocked', report['status'])


if __name__ == '__main__':
    unittest.main()