"""Run each model in a fresh Android process, offline, retaining failures as evidence."""
from pathlib import Path
import io, json, os, subprocess, tarfile, time
from report_archive import extract_reports

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'artifacts/device'
PACKAGE = 'com.yfuse.offlineprobe.v2'
MODELS = ['pp-human', 'biref-base', 'biref-lowmem', 'biref-int8']

def adb(*args, binary=False, timeout=60):
    return subprocess.run(['adb', *args], capture_output=True, text=not binary, timeout=timeout)

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    if adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
        raise SystemExit('Unattended runner only accepts a disposable emulator')
    abis = adb('shell', 'getprop', 'ro.product.cpu.abilist').stdout.strip()
    bridge = adb('shell', 'getprop', 'ro.dalvik.vm.native.bridge').stdout.strip()
    (OUT / 'abi-check.json').write_text(json.dumps(dict(device_abis=abis, native_bridge=bridge, requested_apk='x86'), indent=2))
    if 'x86' not in abis.split(','):
        raise SystemExit('This image lacks native x86 support')
    apk = ROOT / 'app/build/outputs/apk/debug/app-x86-debug.apk'
    result = adb('install', '-r', str(apk), timeout=180)
    (OUT / 'install.txt').write_text(result.stdout + result.stderr); result.check_returncode()
    adb('shell', 'pm', 'clear', PACKAGE)
    registered=adb('shell','pm','list','instrumentation').stdout
    (OUT/'instrumentation-registration.txt').write_text(registered)
    if PACKAGE+'/com.yfuse.offlineprobe.ProbeInstrumentation' not in registered:
        raise SystemExit('Expected instrumentation component is not registered: '+registered)
    offline = []
    for command in [('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1'),
                    ('shell', 'svc', 'wifi', 'disable'), ('shell', 'svc', 'data', 'disable'),
                    ('shell', 'pm', 'disable-user', '--user', '0', 'com.google.android.gms')]:
        result = adb(*command); offline.append(dict(command=command, stdout=result.stdout, stderr=result.stderr, returncode=result.returncode))
    (OUT / 'offline-controls.json').write_text(json.dumps(offline, indent=2))
    (OUT / 'device.txt').write_text(adb('shell', 'getprop').stdout)
    (OUT / 'network.txt').write_text(adb('shell', 'dumpsys', 'connectivity').stdout)
    (OUT / 'package.txt').write_text(adb('shell', 'dumpsys', 'package', PACKAGE).stdout)
    outcomes = []
    for model in MODELS:
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('logcat', '-c')
        command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'model', model, PACKAGE + '/com.yfuse.offlineprobe.ProbeInstrumentation']
        print('TESTING', model, flush=True)
        with (OUT / (model + '-instrumentation.txt')).open('w') as stream:
            process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
            started = time.monotonic(); timed_out = False
            while process.poll() is None:
                if time.monotonic() - started > 240:
                    timed_out = True; adb('shell', 'am', 'force-stop', PACKAGE); process.kill(); break
                time.sleep(1)
            process.wait(timeout=20)
        result = adb('exec-out', 'run-as', PACKAGE, 'cat', f'files/offline-probe/{model}/report.json')
        try: report = json.loads(result.stdout)
        except Exception: report = dict(status='no_report', read_error=result.stderr)
        outcomes.append(dict(model=model, status=report.get('status'), error=report.get('error'), timed_out=timed_out))
        (OUT / (model + '-logcat.txt')).write_text(adb('logcat', '-d', '-v', 'threadtime').stdout)
        (OUT / (model + '-meminfo.txt')).write_text(adb('shell', 'dumpsys', 'meminfo', PACKAGE).stdout)
        (OUT / 'outcomes.json').write_text(json.dumps(outcomes, indent=2))
        print(json.dumps(outcomes[-1]), flush=True)
        snapshot = adb('exec-out', 'run-as', PACKAGE, 'tar', '-C', 'files', '-cf', '-', 'offline-probe', binary=True, timeout=120)
        if snapshot.returncode == 0:
            with tarfile.open(fileobj=io.BytesIO(snapshot.stdout)) as partial:
                extract_reports(partial, OUT)
    archive = adb('exec-out', 'run-as', PACKAGE, 'tar', '-C', 'files', '-cf', '-', 'offline-probe', binary=True, timeout=180)
    archive.check_returncode()
    with tarfile.open(fileobj=io.BytesIO(archive.stdout)) as tar:
        extract_reports(tar, OUT)
    print('All attempted:', json.dumps(outcomes), flush=True)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write('Android 11 native x86 emulator CPU; no ARM64 physical-device performance claim.\n\n')
            stream.write('| Model | Status |\n|---|---|\n')
            for item in outcomes: stream.write(f'| {item["model"]} | {item["status"]} |\n')

if __name__ == '__main__': main()
