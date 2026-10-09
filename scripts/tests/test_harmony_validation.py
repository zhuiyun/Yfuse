"""Verify incomplete coverage cannot be mistaken for a successful SDK check."""

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


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'scripts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


HOST = load('verify-cangjie-host')
PORT = load('verify-harmony-port')
RELEASE = load('harmony-release-gate')


class CangjieCoverageTest(unittest.TestCase):
    def execute(self, *, compile_failure=None, strict=False):
        def command(args, cwd, env):
            if '--version' in args:
                return subprocess.CompletedProcess(args, 0, 'cjc test-version\n', '')
            if '--output-type=staticlib' in args:
                package = args[args.index('--package') + 1]
                if package == compile_failure:
                    return subprocess.CompletedProcess(args, 1, '', "can not find package 'stdx.encoding.json'")
                if package == 'data' and compile_failure == 'compiler-error':
                    return subprocess.CompletedProcess(args, 1, '', 'syntax error')
            if len(args) == 1:
                return subprocess.CompletedProcess(args, 0, 'TOTAL: 2 PASSED: 2 FAILED: 0 SKIPPED: 0\n', '')
            return subprocess.CompletedProcess(args, 0, '', '')
        with tempfile.TemporaryDirectory() as temp:
            report = Path(temp) / 'report.json'
            args = ['--report', str(report)] + (['--require-complete'] if strict else [])
            with patch.object(HOST, 'find_toolchain', return_value=Path(temp)), \
                 patch.object(HOST, 'stage_sources', return_value=Path(temp)), \
                 patch.object(HOST, 'run', side_effect=command), contextlib.redirect_stdout(io.StringIO()) as output:
                code = HOST.main(args)
            return code, json.loads(report.read_text()), output.getvalue()

    def test_missing_compiler_is_skipped_and_strict_mode_rejects_it(self):
        for strict, expected in ((False, 0), (True, 2)):
            with self.subTest(strict=strict), tempfile.TemporaryDirectory() as temp:
                report_path = Path(temp) / 'report.json'
                with patch.object(HOST, 'find_toolchain', return_value=None), contextlib.redirect_stdout(io.StringIO()) as output:
                    code = HOST.main(['--report', str(report_path)] + (['--require-complete'] if strict else []))
                report = json.loads(report_path.read_text())
                self.assertEqual(expected, code)
                self.assertEqual('skipped', report['status'])
                self.assertEqual(12, len(report['plannedPackages']))
                self.assertNotIn('PASS Cangjie host verification', output.getvalue())

    def test_partial_coverage_remains_partial_in_report_and_console(self):
        code, report, output = self.execute(compile_failure='network')
        self.assertEqual(0, code)
        self.assertEqual('partial', report['status'])
        self.assertEqual(1, report['counts']['packages']['blocked'])
        self.assertEqual(11, report['counts']['packages']['passed'])
        self.assertEqual(2, report['tests'][0]['counts']['passed'])
        self.assertIn('PARTIAL Cangjie host verification', output)
        self.assertNotIn('PASS Cangjie host verification', output)
        self.assertFalse(report['platformValidated'])

    def test_colored_compiler_summary_keeps_numeric_counts(self):
        counts = HOST.test_counts("TOTAL: 16 \x1b[32mPASSED: \x1b[0m16 FAILED: 0")
        self.assertEqual({'total': 16, 'passed': 16, 'failed': 0}, counts)

    def test_strict_partial_coverage_exits_two(self):
        code, report, _ = self.execute(compile_failure='network', strict=True)
        self.assertEqual(2, code)
        self.assertEqual(code, report['exitCode'])

    def test_complete_host_check_still_does_not_claim_platform_validation(self):
        code, report, output = self.execute(strict=True)
        self.assertEqual(0, code)
        self.assertEqual('passed', report['status'])
        self.assertEqual(12, report['counts']['packages']['passed'])
        self.assertEqual(3, report['counts']['tests']['passed'])
        self.assertFalse(report['platformValidated'])
        self.assertIn('PASS Cangjie host verification', output)

    def test_real_compiler_failure_is_not_an_environment_skip(self):
        code, report, _ = self.execute(compile_failure='compiler-error')
        self.assertEqual(1, code)
        self.assertEqual('failed', report['status'])
        self.assertEqual('blocked', next(item for item in report['tests'] if item['package'] == 'app')['status'])


class AggregateCoverageTest(unittest.TestCase):
    def execute(self, host_status, strict=False, source_error=None):
        with tempfile.TemporaryDirectory() as temp:
            report_path = Path(temp) / 'aggregate.json'
            with contextlib.ExitStack() as stack:
                for name in ('check_contracts', 'check_scaffold', 'check_fixtures', 'check_cangjie_sources', 'check_native_core'):
                    stack.enter_context(patch.object(PORT, name, return_value=None, side_effect=source_error if name == 'check_contracts' else None))
                stack.enter_context(patch.object(PORT, 'check_cangjie_host_build', return_value={'status': host_status}))
                output = stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
                code = PORT.main(['--report', str(report_path)] + (['--require-host'] if strict else []))
            return code, json.loads(report_path.read_text()), output.getvalue()

    def test_optional_host_skip_is_not_printed_as_pass(self):
        code, report, output = self.execute('skipped')
        self.assertEqual(0, code)
        self.assertEqual('partial', report['status'])
        self.assertIn('SKIP Cangjie host build and tests', output)
        self.assertNotIn('PASS Cangjie host build and tests', output)

    def test_strict_check_rejects_skipped_or_partial_host_results(self):
        for status in ('skipped', 'partial'):
            with self.subTest(status=status):
                code, report, _ = self.execute(status, strict=True)
                self.assertEqual(2, code)
                self.assertEqual('partial', report['status'])

    def test_source_dependency_block_is_not_a_business_assertion_failure(self):
        code, report, _ = self.execute('passed', source_error=PORT.CheckBlocked('missing yaml'))
        self.assertEqual(2, code)
        self.assertEqual('blocked', report['status'])
        self.assertEqual('blocked', report['checks'][0]['status'])

    def test_real_failure_takes_precedence_over_partial_host_coverage(self):
        code, report, _ = self.execute('partial', source_error=AssertionError('broken contract'))
        self.assertEqual(1, code)
        self.assertEqual('failed', report['status'])

    def test_failed_host_result_fails_the_aggregate(self):
        code, report, _ = self.execute('failed')
        self.assertEqual(1, code)
        self.assertEqual('failed', report['status'])

    def test_complete_checks_pass_without_claiming_a_hap(self):
        code, report, _ = self.execute('passed', strict=True)
        self.assertEqual(0, code)
        self.assertEqual('passed', report['status'])
        self.assertFalse(report['platformValidated'])

    def test_missing_yaml_has_an_actionable_block(self):
        with patch.object(PORT, 'yaml', None), self.assertRaisesRegex(PORT.CheckBlocked, 'requirements-validation.txt'):
            PORT.check_contracts()


class HarmonyReleaseCoverageTest(unittest.TestCase):
    def test_release_requires_complete_host_coverage_before_other_release_checks(self):
        blocked = subprocess.CalledProcessError(2, ['verify-harmony-port.py'])
        with patch.object(RELEASE.subprocess, 'run', side_effect=blocked) as command, \
             patch.object(RELEASE, 'check') as later_check, self.assertRaises(subprocess.CalledProcessError):
            RELEASE.main()
        self.assertIn('--require-host', command.call_args.args[0])
        later_check.assert_not_called()


class HarmonyWorkflowCoverageTest(unittest.TestCase):
    def test_main_branch_and_verifier_changes_trigger_source_gates(self):
        if PORT.yaml is None:
            self.fail('Install scripts/requirements-validation.txt to validate workflow triggers')
        workflow = PORT.yaml.load((ROOT / '.github/workflows/harmony-source-gates.yml').read_text(), Loader=PORT.yaml.BaseLoader)
        self.assertIn('master', workflow['on']['push']['branches'])
        for event in ('push', 'pull_request'):
            with self.subTest(event=event):
                paths = workflow['on'][event]['paths']
                for path in ('scripts/verify-cangjie-host.py', 'scripts/verify-harmony-port.py',
                             'scripts/check-validation-env.py', 'scripts/requirements-validation.txt',
                             'scripts/tests/test_harmony_validation.py', 'scripts/tests/test_validation_env.py'):
                    self.assertIn(path, paths)
        steps = workflow['jobs']['verify']['steps']
        self.assertTrue(any('--report build/validation/harmony-source.json' in step.get('run', '') for step in steps))
        self.assertTrue(any(step.get('if') == 'always()' and 'upload-artifact@' in step.get('uses', '') for step in steps))


if __name__ == '__main__':
    unittest.main()