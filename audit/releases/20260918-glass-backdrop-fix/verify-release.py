"""Verify the actual signed APK and its predecessor without reading signing credentials."""
import hashlib
import json
import re
import struct
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
OUT = ROOT / 'artifacts/releases/glass-backdrop-fix-1.0.73-235'
APK = OUT / 'Yfuse-1.0.73-235-full-arm64-signed.apk'
PREVIOUS = ROOT / 'artifacts/releases/glass-presets-1.0.72-234/Yfuse-1.0.72-234-full-arm64-signed.apk'
TOOLS = Path('D:/AndroidSDK/build-tools/36.0.0')
JAVA = Path('C:/Users/app-inkbird/.jdks/ms-21.0.9/bin/java.exe')
CERT = '373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84'


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(arguments, filename):
    result = subprocess.run([str(value) for value in arguments], capture_output=True, check=True)
    output = result.stdout.decode('utf-8', errors='replace')
    (OUT / filename).write_text(output, encoding='utf-8')
    return output


def metadata(apk, filename):
    badging = run([TOOLS / 'aapt.exe', 'dump', 'badging', apk], filename)
    package = re.search(r'^package: (.+)$', badging, re.M)
    require(package is not None, 'APK package metadata missing')
    return dict(re.findall(r"(\w+)='([^']*)'", package.group(1))), badging


def signature(apk, filename):
    result = run([JAVA, '-jar', TOOLS / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk], filename)
    require('Verified using v2 scheme (APK Signature Scheme v2): true' in result, 'APK v2 signature not verified')
    require(re.search(r'^Number of signers: 1\s*$', result, re.M), 'Unexpected signer count')
    require(f'Signer #1 certificate SHA-256 digest: {CERT}' in result, 'Unexpected production signing certificate')


def elf_alignment(content, name):
    """Verify actual AArch64 ELF load segments independently of APK ZIP alignment."""
    require(len(content) >= 64 and content[:4] == b'\x7fELF', f'Invalid ELF header: {name}')
    require(content[4] == 2, f'Expected ELF64 native library: {name}')
    require(content[5] in (1, 2), f'Unsupported ELF byte order: {name}')
    require(content[6] == 1, f'Unsupported ELF identification version: {name}')
    endian = '<' if content[5] == 1 else '>'
    header = struct.unpack_from(endian + 'HHIQQQIHHHHHH', content, 16)
    _, machine, version, _, phoff, _, _, ehsize, phentsize, phnum, _, _, _ = header
    require(machine == 183 and version == 1, f'Expected AArch64 ELF version 1: {name}')
    require(ehsize == 64 and phentsize == 56, f'Invalid ELF64 header sizes: {name}')
    require(0 < phnum < 0xffff, f'Missing or unsupported extended ELF program headers: {name}')
    require(phoff >= ehsize and phoff + phnum * phentsize <= len(content),
            f'ELF program header table is out of bounds: {name}')
    segments = []
    for index in range(phnum):
        kind, _, offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            endian + 'IIQQQQQQ', content, phoff + index * phentsize)
        if kind != 1:  # PT_LOAD
            continue
        require(offset <= len(content) and file_size <= len(content) - offset,
                f'ELF PT_LOAD {index} file range is out of bounds: {name}')
        require(memory_size >= file_size, f'ELF PT_LOAD {index} memory size is invalid: {name}')
        require(alignment >= 16 * 1024 and alignment & (alignment - 1) == 0,
                f'ELF PT_LOAD {index} is not 16 KB aligned (p_align={alignment}): {name}')
        require((address - offset) % alignment == 0,
                f'ELF PT_LOAD {index} address/offset alignment differs: {name}')
        segments.append({'index': index, 'offset': offset, 'virtualAddress': address,
                         'fileBytes': file_size, 'memoryBytes': memory_size, 'alignmentBytes': alignment})
    require(segments, f'ELF has no PT_LOAD segments: {name}')
    return {'class': 64, 'machine': 'AArch64', 'byteOrder': 'little' if endian == '<' else 'big',
            'minimumLoadAlignmentBytes': min(segment['alignmentBytes'] for segment in segments),
            'loadSegments': segments, 'alignment16KbVerified': True}


require(APK.is_file() and PREVIOUS.is_file(), 'Expected current and previous APKs')
log = (AUDIT / 'build.log').read_text(encoding='utf-8-sig')
require(re.findall(r'^BUILD (SUCCESSFUL|FAILED)\b', log, re.M)[-1:] == ['SUCCESSFUL'], 'Release build did not succeed')
for task in ('verifyDesignSystemUsage', 'assembleRelease', 'verifyReleaseSigning', 'verifyReleaseMetadata',
             'verifyReleasePlaybackRuntime', 'verifyProductionMdkRights', 'verifyProductionYCoreGpu',
             'verifyCustomMpvArtifact', 'verifyStandaloneYCoreArtifact', 'verifyMdkArtifact'):
    require(re.search(rf'^> Task :composeApp:{task}(?: UP-TO-DATE)?\s*$', log, re.M), f'Required task missing: {task}')

previous, _ = metadata(PREVIOUS, 'previous-badging.txt')
signature(PREVIOUS, 'previous-signature.txt')
require(previous.get('versionName') == '1.0.72' and previous.get('versionCode') == '234',
        'Expected verified latest delivered baseline 1.0.72 (234)')
current, badging = metadata(APK, 'badging.txt')
signature(APK, 'signature.txt')
properties = dict(re.findall(r'^(VERSION_NAME|VERSION_CODE)=(.+)$', (ROOT / 'version.properties').read_text(), re.M))
properties = {key: value.strip() for key, value in properties.items()}
require(current.get('name') == previous.get('name') == 'com.yfuse', 'Application ID changed')
require(current.get('versionName') == properties['VERSION_NAME'] == '1.0.73', 'Version name mismatch')
require(current.get('versionCode') == properties['VERSION_CODE'] == '235', 'Version code mismatch')
require(current.get('compileSdkVersion') == '37', 'Expected API 37 compilation')
require("sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging, 'Android SDK compatibility changed')
manifest = run([TOOLS / 'aapt.exe', 'dump', 'xmltree', APK, 'AndroidManifest.xml'], 'manifest.txt')
require('com.yfuse.shell.PhoneApplication' in manifest, 'AGP 9 application shell missing from packaged manifest')
resources = run([TOOLS / 'aapt2.exe', 'dump', 'resources', APK], 'resources.txt')
policy_resource = re.search(r'resource\s+0x[0-9a-f]+\s+xml/network_security_config\s*\n\s*\(\)\s+\(file\)\s+(\S+)', resources)
require(policy_resource is not None, 'Packaged network security configuration missing')
network_policy = run([TOOLS / 'aapt2.exe', 'dump', 'xmltree', '--file', policy_resource.group(1), APK], 'network-policy.txt')
require('cleartextTrafficPermitted=true' in network_policy, 'Media HTTP compatibility missing')
require('47.112.219.60' not in network_policy, 'Shared media/account IP must not be blocked by host-level policy')
require(all(host in network_policy for host in ('themoviedb.org', 'tmdb.org', 'trakt.tv', 'plex.tv')),
        'Official metadata provider network policy changed')
require(tuple(map(int, current['versionName'].split('.'))) > tuple(map(int, previous['versionName'].split('.'))),
        'Version name must increase over the actual previous APK')
require(int(current['versionCode']) > int(previous['versionCode']), 'Version code must increase over previous APK')
require((ROOT / 'release-notes.txt').read_text(encoding='utf-8-sig').splitlines()[0] == current['versionName'],
        'Release notes first line must match APK version')
for name in ('version.properties', 'release-notes.txt'):
    require((ROOT / name).read_bytes() == (OUT / name).read_bytes(), f'Delivery metadata differs: {name}')
require("native-code: 'arm64-v8a'" in badging, 'Expected ARM64 APK')
require('application-debuggable' not in badging, 'Production APK must not be debuggable')
alignment = run([TOOLS / 'zipalign.exe', '-c', '-P', '16', '-v', '4', APK], 'alignment.txt')
require('Verification successful' in alignment, 'APK 16 KB ZIP alignment failed')

native = []
ycore = []
baseline_native = json.loads((ROOT / 'audit/releases/20260915-version-correction/native-baseline/installed-verification.json').read_text(encoding='utf-8'))
require(baseline_native['matchesDelivered226Libraries'], 'Installed native baseline was not verified')
require(len(baseline_native['installedLibraries']) == 12, 'Incomplete native baseline manifest')
with zipfile.ZipFile(APK) as package, zipfile.ZipFile(PREVIOUS) as previous_package, \
        zipfile.ZipFile(ROOT / 'composeApp/libs/ycore-native.aar') as source:
    names = package.namelist()
    for asset in ('Anime4K_Upscale_Original_x2.glsl', 'LICENSE'):
        require(package.read('assets/anime4k/' + asset) == (ROOT / 'composeApp/src/androidMain/assets/anime4k' / asset).read_bytes(), 'Anime4K asset missing or changed: ' + asset)
    require(len(names) == len(set(names)), 'Duplicate ZIP entries')
    require(package.testzip() is None, 'APK CRC failure')
    libraries = {name for name in names if name.startswith('lib/') and name.endswith('.so')}
    previous_libraries = {name for name in previous_package.namelist() if name.startswith('lib/') and name.endswith('.so')}
    require(libraries == previous_libraries, f'Native library set changed: {sorted(libraries ^ previous_libraries)}')
    required = {'libmpv.so', 'libmdk.so', 'libyfuse-mdk-jni.so', 'libycore_demux.so', 'libycore_gpu.so'}
    require(required <= {Path(name).name for name in libraries}, 'Full runtime libraries missing')
    require(all(name.startswith('lib/arm64-v8a/') for name in libraries), 'Unexpected native ABI')
    for name, expected_digest in baseline_native['installedLibraries'].items():
        entry = 'lib/arm64-v8a/' + name
        require(digest(package.read(entry)) == expected_digest, f'Packaged native differs from verified baseline: {name}')
        require(package.read(entry) == previous_package.read(entry), f'Packaged native differs from actual 234 APK: {name}')
    for name in sorted(libraries):
        content = package.read(name)
        native.append({'path': name, 'bytes': len(content), 'sha256': digest(content),
                       'elf': elf_alignment(content, name)})
    ycore_entries = [name for name in source.namelist() if name.startswith('jni/arm64-v8a/') and name.endswith('.so')]
    require(len(ycore_entries) == 8, 'Unexpected YCore native source contents')
    for name in sorted(ycore_entries):
        content = source.read(name)
        require(package.read('lib/' + name.removeprefix('jni/')) == content, f'YCore native source differs: {name}')
        ycore.append({'source': name, 'sha256': digest(content), 'byteIdentical': True})

data = APK.read_bytes()
report = {
    'verifiedAtUtc': datetime.now(timezone.utc).isoformat(),
    'apk': APK.name, 'applicationId': current['name'], 'versionName': current['versionName'],
    'versionCode': int(current['versionCode']), 'previousVersionName': previous['versionName'],
    'previousVersionCode': int(previous['versionCode']), 'previousSha256': digest(PREVIOUS.read_bytes()),
    'projectMetadataMatches': True, 'releaseNotesMatch': True,
    'packagedNetworkPolicyVerified': True,
    'compileSdk': 37, 'targetSdk': 36, 'minSdk': 26, 'applicationShellVerified': True,
    'packageProfile': 'full', 'abi': 'arm64-v8a', 'bytes': len(data), 'sha256': digest(data),
    'certificateSha256': CERT, 'signatureVerified': True, 'signatureV2': True, 'debuggable': False,
    'alignmentVerified': True, 'zip16KbAlignmentVerified': True, 'elf16KbAlignmentVerified': True,
    'zipCrcVerified': True, 'nativeLibraries': native,
    'ycoreByteIdentityChecks': ycore, 'sourceFileCount': len(json.loads((AUDIT / 'source-hashes.json').read_text())),
    'nativeBaselineCommit': baseline_native['sourceCommit'],
    'matchesPreviousDeliveredNativeLibraries': True, 'previousNativeLibrariesCompared': 12,
    'tests': json.loads((AUDIT / 'test-summary.json').read_text(encoding='utf-8')),
    'playbackTested': False, 'installed': False, 'published': False,
    'updateManifestPublicKeyConfigured': 'production signing without an update-manifest public key' not in log,
}
(OUT / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(OUT / 'SHA256.txt').write_text(f'{report["sha256"]}  {APK.name}\n', encoding='utf-8')
(AUDIT / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: report[key] for key in ('apk', 'versionName', 'versionCode', 'bytes', 'sha256', 'signatureVerified')},
                 ensure_ascii=False, indent=2))
print('PASS: version increase, project metadata, production certificate, ARM64 full runtime, YCore bytes, ZIP and ELF alignment')

