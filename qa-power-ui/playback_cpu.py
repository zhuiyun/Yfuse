"""Attribute foreground CPU to app threads and loaded libraries on the cloud guest."""
import os
from pathlib import Path, PurePosixPath
import re
import subprocess


def report_rows(report):
    rows = []
    for line in report.splitlines():
        match = re.match(r'^\s*(\d+(?:\.\d+)?)%\s+(.+?)\s{2,}(\S.*)$', line)
        if match:
            share, name, dso = match.groups()
            rows.append({'percentOfSampledAppCpu': float(share), 'thread': name.strip(),
                         'library': PurePosixPath(dso.strip()).name})
    return rows


def profile():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('ANDROID_SERIAL') != '127.0.0.1:15555':
        raise RuntimeError('The playback CPU profile requires the disposable cloud guest')

    def run(*args, timeout=20):
        return subprocess.run(['adb', *args], capture_output=True, text=True, errors='replace', timeout=timeout)

    def shell(*args, timeout=20):
        return run('shell', *args, timeout=timeout)

    renderer = shell('getprop', 'ro.hardware.egl').stdout.strip()
    pid = shell('pidof', 'com.yfuse').stdout.strip()
    result = {'package': 'com.yfuse', 'androidEglDriver': renderer, 'durationSeconds': 20,
              'event': 'task-clock:u', 'frequencyHz': 49}
    if not pid.isdecimal() or shell('cat', '/proc/' + pid + '/cmdline').stdout.rstrip('\0\r\n') != 'com.yfuse':
        return dict(result, status='no-unique-player-process')
    temp = shell('mktemp', '-d', '/data/local/tmp/yfuse-cpu-XXXXXXXX').stdout.strip()
    if not re.fullmatch(r'/data/local/tmp/yfuse-cpu-[A-Za-z0-9]{8}', temp):
        return dict(result, status='temporary-directory-unavailable')
    binary, data = temp + '/simpleperf', temp + '/playback.data'
    try:
        profiler = '/system/bin/simpleperf'
        if shell('test', '-x', profiler).returncode != 0:
            ndks = [Path(os.environ[n]) for n in ('ANDROID_NDK_HOME', 'ANDROID_NDK_LATEST_HOME', 'ANDROID_NDK_ROOT')
                    if os.environ.get(n)]
            if os.environ.get('ANDROID_HOME'):
                ndks.extend(sorted((Path(os.environ['ANDROID_HOME']) / 'ndk').glob('*'), reverse=True))
            tools = [ndk / 'simpleperf/bin/android/arm64/simpleperf' for ndk in ndks]
            source = next((path for path in tools if path.is_file()), None)
            if not source or run('push', str(source), binary).returncode or shell('chmod', '700', binary).returncode:
                return dict(result, status='android-simpleperf-unavailable')
            profiler = binary
        capture = shell(profiler, 'record', '-e', result['event'], '-f', str(result['frequencyHz']),
                        '-p', pid, '--duration', str(result['durationSeconds']), '-o', data, timeout=40)
        if capture.returncode:
            cause = capture.stderr + capture.stdout
            denied = re.search(r'permission denied|operation not permitted|not supported|not allowed', cause, re.I)
            return dict(result, status='sampling-unavailable', reason=denied[0].lower() if denied else None,
                        exitCode=capture.returncode)
        report = shell(profiler, 'report', '-i', data, '--sort', 'comm,dso')
        rows = report_rows(report.stdout)
        samples = re.search(r'^Samples recorded:\s*(\d+)', report.stdout, re.M)
        return dict(result, status='recorded' if rows and not report.returncode else 'report-unavailable',
                    totalSamples=int(samples[1]) if samples else None, groups=rows)
    except (OSError, subprocess.SubprocessError) as error:
        return dict(result, status='profile-unavailable', errorType=type(error).__name__)
    finally:
        shell('rm', '-f', data, binary)
        shell('rmdir', temp)
