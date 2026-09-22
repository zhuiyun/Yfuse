"""Verify the downloaded native artifact against the delivered 226 APK and source baseline."""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[3]
BASELINE = AUDIT.parent / 'baseline-source'
PREVIOUS = AUDIT.parent / 'previous-1.0.64-226'
ARTIFACTS = AUDIT / 'extracted'
COMMIT = '8dc1e5b22fbd74f014670bf07c1773891f719191'
EXPECTED_ZIP = '07d8eeedfa3a8feb3e4f6945105cd0f771ab29f9e918d3f7a2028bd90ba93f81'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise SystemExit(message)


archive = AUDIT / f'signing-native-{COMMIT}.zip'
require(sha(archive.read_bytes()) == EXPECTED_ZIP, 'Workflow artifact ZIP checksum differs')
info = json.loads((ARTIFACTS / 'native-build-info.json').read_text())
require(info['sourceCommit'] == COMMIT, 'Native artifact source commit differs')
previous_info = json.loads((PREVIOUS / 'native-build-info.json').read_text())
require(info == previous_info, 'Native provenance differs from the delivered 226 artifact')
demux = BASELINE / 'scripts/native/ycore_demux_jni.cpp'
require(sha(demux.read_bytes()) == info['demuxSourceSha256'], 'Baseline demux source digest differs')

inputs = {p.relative_to(BASELINE) for p in (BASELINE / 'scripts/native').rglob('*') if p.is_file()}
inputs.update(Path('scripts') / name for name in (
    'build-current-ycore-native.sh', 'build-ycore-native.sh', 'build-ycore-demux.sh',
    'build-yfuse-mpv-bluray.sh', 'build-yfuse-mpv-dolby.sh', 'package-ycore-native-aar.py',
    'install-ycore-native.sh', 'install-yfuse-mpv-bluray.sh', 'verify-ycore-native.sh',
    'verify-yfuse-mpv-bluray-aar.sh', 'verify-yfuse-mpv-dolby-aar.sh',
    'engine-checksums.sha256', 'yfuse-mpv-sources.txt',
))
source_hashes = {}
installer_line_endings = {}
for relative in sorted(inputs):
    baseline = (BASELINE / relative).read_bytes()
    current = (ROOT / relative).read_bytes()
    if relative.as_posix() == 'scripts/install-ycore-native.sh' and current != baseline:
        require(current.replace(b'\r\n', b'\n') == baseline.replace(b'\r\n', b'\n'),
                f'Installation helper content differs from baseline: {relative}')
        installer_line_endings[relative.as_posix()] = {
            'baselineSha256': sha(baseline), 'workingSha256': sha(current),
            'difference': 'CRLF versus LF only; Git Bash igncr enabled, script unchanged',
        }
    else:
        require(current == baseline, f'Working native source differs from baseline: {relative}')
    source_hashes[relative.as_posix()] = sha(baseline)

libraries = {}
aars = {}
for filename in ('libmpv-yfuse-bluray.aar', 'ycore-native.aar', 'ycore-gpu.aar'):
    path = ARTIFACTS / filename
    digest = sha(path.read_bytes())
    require(digest == (ARTIFACTS / (filename + '.sha256')).read_text().split()[0].lower(),
            f'AAR checksum differs: {filename}')
    aars[filename] = digest
    with zipfile.ZipFile(path) as package:
        require(len(package.namelist()) == len(set(package.namelist())), f'Duplicate AAR entry: {filename}')
        require(package.testzip() is None, f'AAR CRC failure: {filename}')
        for name in package.namelist():
            if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):
                key = name.rsplit('/', 1)[-1]
                digest = sha(package.read(name))
                require(key not in libraries or libraries[key] == digest, f'Conflicting native library: {key}')
                libraries[key] = digest
require(libraries == info['libraries'], 'Actual native library hashes differ from provenance')
with zipfile.ZipFile(PREVIOUS / 'Yfuse-226-1.0.64-signed.apk') as package:
    for name, digest in libraries.items():
        require(sha(package.read('lib/arm64-v8a/' + name)) == digest,
                f'Native library differs from the delivered 226 APK: {name}')

backup = AUDIT / 'previous-installed'
backup.mkdir(exist_ok=True)
old_hashes = {}
for stem in ('libmpv-release', 'ycore-native', 'ycore-gpu'):
    for suffix in ('.aar', '.aar.sha256', '.sources.txt'):
        name = stem + suffix
        original = ROOT / 'composeApp/libs' / name
        saved = backup / name
        if not saved.exists():
            shutil.copy2(original, saved)
        old_hashes[name] = sha(saved.read_bytes())
report = {
    'workflowRunId': 34701134934, 'artifactId': 10299938547,
    'artifactZipSha256': EXPECTED_ZIP, 'sourceCommit': COMMIT, 'sourceTree': info['sourceTree'],
    'demuxSourceSha256': info['demuxSourceSha256'], 'baselineInputs': source_hashes,
    'installerLineEndings': installer_line_endings,
    'aarSha256': aars, 'libraries': libraries, 'matchesDelivered226Libraries': True,
    'previousInstalledSha256': old_hashes,
}
(AUDIT / 'input-verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(f'PASS: artifact digest, {len(inputs)} baseline source inputs, {len(aars)} AARs, '
      f'{len(libraries)} libraries identical to delivered 1.0.64 (226)')
