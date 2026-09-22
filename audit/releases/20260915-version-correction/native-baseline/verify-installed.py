"""Read-only checks of the installed carriers; write only this task's audit report."""
import hashlib
import json
import zipfile
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[3]
ARTIFACTS = AUDIT / 'extracted'
LIBS = ROOT / 'composeApp/libs'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise SystemExit(message)


inputs = json.loads((AUDIT / 'input-verification.json').read_text())
installed = {}
for stem in ('libmpv-release', 'ycore-native', 'ycore-gpu'):
    for suffix in ('.aar', '.aar.sha256', '.sources.txt'):
        name = stem + suffix
        installed[name] = sha((LIBS / name).read_bytes())
    require(installed[stem + '.aar'] == (LIBS / (stem + '.aar.sha256')).read_text().split()[0].lower(),
            f'Installed checksum sidecar differs: {stem}')
    require((LIBS / (stem + '.sources.txt')).read_bytes() == (ARTIFACTS / 'NATIVE-SOURCES.txt').read_bytes(),
            f'Installed source manifest differs: {stem}')
for name in ('ycore-native.aar', 'ycore-gpu.aar'):
    require((LIBS / name).read_bytes() == (ARTIFACTS / name).read_bytes(), f'Installed artifact bytes differ: {name}')

with zipfile.ZipFile(ARTIFACTS / 'libmpv-yfuse-bluray.aar') as carrier, \
        zipfile.ZipFile(LIBS / 'libmpv-release.aar') as actual, zipfile.ZipFile(LIBS / 'ycore-gpu.aar') as gpu:
    removed = {name for name in carrier.namelist() if name.startswith('jni/') and name.endswith('/libycore_gpu.so')}
    require(removed, 'Expected GPU library split')
    require(set(actual.namelist()) == set(carrier.namelist()) - removed, 'Carrier split changed unexpected entries')
    for name in removed:
        require(carrier.read(name) == gpu.read(name), f'Split GPU library differs: {name}')
    for name in actual.namelist():
        require(actual.read(name) == carrier.read(name), f'Installed carrier entry differs: {name}')

libraries = {}
for filename in ('libmpv-release.aar', 'ycore-native.aar', 'ycore-gpu.aar'):
    with zipfile.ZipFile(LIBS / filename) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), f'Duplicate entry: {filename}')
        require(archive.testzip() is None, f'CRC failure: {filename}')
        for name in archive.namelist():
            if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):
                key = name.rsplit('/', 1)[-1]
                digest = sha(archive.read(name))
                require(key not in libraries or libraries[key] == digest, f'Conflicting installed library: {key}')
                libraries[key] = digest
require(libraries == inputs['libraries'], 'Installed library hashes differ from the verified 226 baseline')

pins = []
for line in (ROOT / 'scripts/engine-checksums.sha256').read_text().splitlines():
    if not line.strip() or line.startswith('#'):
        continue
    expected, name = line.split()
    path = LIBS / name
    # Dolby/stable aliases are authenticated download choices, not separately installed AARs.
    actual = sha(path.read_bytes()) if path.is_file() else None
    pins.append({'name': name, 'configuredSha256': expected.lower(), 'actualSha256': actual,
                 'installed': path.is_file(), 'matches': expected.lower() == actual if actual else None})
temporary = [str(path.relative_to(AUDIT)) for path in (AUDIT / 'tmp').rglob('*')]
require(not temporary, 'Native gate temporary files were not cleaned')
report = {
    'artifactId': inputs['artifactId'], 'workflowRunId': inputs['workflowRunId'],
    'sourceCommit': inputs['sourceCommit'], 'sourceTree': inputs['sourceTree'],
    'artifactZipSha256': inputs['artifactZipSha256'], 'installedSha256': installed,
    'installedLibraries': libraries, 'matchesDelivered226Libraries': True,
    'carrierModification': 'Only the byte-identical libycore_gpu.so is removed; ycore-gpu.aar packages it once',
    'repositoryGates': ['verify-yfuse-mpv-bluray-aar.sh', 'verify-yfuse-mpv-dolby-aar.sh', 'verify-ycore-native.sh'],
    'repositoryGatesExitCode': 0, 'repositoryGateLog': 'install.log',
    'enginePins': pins, 'temporaryFilesCleaned': True,
    'installedToDevice': False, 'playbackRun': False, 'gradleRunByThisTask': False,
}
(AUDIT / 'installed-verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'installedAars': {name: digest for name, digest in installed.items() if name.endswith('.aar')},
                  'libraryCount': len(libraries), 'pins': pins, 'temporaryFilesCleaned': True}, indent=2))
