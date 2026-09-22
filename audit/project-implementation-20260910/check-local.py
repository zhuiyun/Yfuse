import importlib.util
import subprocess
import sys
from pathlib import Path

import yaml

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('harmony_verify', root / 'scripts/verify-harmony-port.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
for name in ('check_contracts', 'check_scaffold', 'check_fixtures', 'check_cangjie_sources'):
    getattr(module, name)()
    print('PASS', name, flush=True)
workflow = yaml.safe_load((root / '.github/workflows/quality-gates-v2.yml').read_text(encoding='utf-8'))
assert {'fast-jvm', 'fast-contracts', 'verify'} <= workflow['jobs'].keys()
print('PASS CI YAML and independent jobs', flush=True)
for args in (
    ['scripts/release_metadata.py'],
    ['scripts/verify-module-boundaries.py'],
    ['scripts/verify-tv-source.py', '--manifest', 'tvApp/src/androidMain/AndroidManifest.xml', '--banner', 'tvApp/src/main/res/drawable-nodpi/tv_banner.png'],
    ['-m', 'unittest', 'discover', '-s', 'scripts/tests', '-p', 'test_*.py'],
    ['-m', 'unittest', 'discover', '-s', 'scripts', '-p', 'test_android_performance.py'],
):
    subprocess.run([sys.executable, *args], cwd=root, check=True)
