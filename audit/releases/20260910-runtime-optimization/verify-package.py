"""Verify the finished release without reading credentials or connecting a device."""
import hashlib
import json
import re
import zipfile
from datetime import datetime, timezone
from pathlib import Path

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
APK = OUT / 'Yfuse-1.0.50-signed-arm64.apk'
CERT = '373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84'
NATIVE = {
    'libandroidx.graphics.path.so', 'libass.so', 'libavcodec.so', 'libavdevice.so',
    'libavfilter.so', 'libavformat.so', 'libavutil.so', 'libc++_shared.so', 'libdav1d.so',
    'libffmpeg.so', 'libimage_processing_util_jni.so', 'libmdk.so', 'libmpv.so', 'libplayer.so',
    'libsurface_util_jni.so', 'libswresample.so', 'libswscale.so', 'libycore_demux.so',
    'libycore_gpu.so', 'libyfuse-mdk-jni.so',
}

def require(value, message):
    if not value:
        raise SystemExit(message)

def text(name):
    return (OUT / name).read_text(encoding='utf-8-sig')

def digest(data):
    return hashlib.sha256(data).hexdigest()

log = text('build.log')
outcomes = re.findall(r'^BUILD (SUCCESSFUL|FAILED)\b', log, re.M)
require(outcomes and outcomes[-1] == 'SUCCESSFUL', 'Release build did not succeed')
for task in ('verifyDesignSystemUsage', 'assembleRelease'):
    require(re.search(rf'^> Task :composeApp:{task}(?: UP-TO-DATE)?\s*$', log, re.M), f'Missing successful task: {task}')
badging = text('badging.txt')
values = dict(re.findall(r"(\w+)='([^']*)'", re.search(r'^package: (.+)$', badging, re.M).group(1)))
require(values.get('name') == 'com.yfuse', 'Wrong application ID')
require(values.get('versionName') == '1.0.50' and values.get('versionCode') == '212', 'Wrong version')
require("native-code: 'arm64-v8a'" in badging, 'Wrong ABI')
require('application-debuggable' not in badging, 'Package is debuggable')
signature = text('signature.txt')
require('Verified using v2 scheme (APK Signature Scheme v2): true' in signature, 'APK v2 signature failed')
require(f'Signer #1 certificate SHA-256 digest: {CERT}' in signature, 'Unexpected signing certificate')
require('Number of signers: 1' in signature, 'Unexpected signer count')
require('Verification successful' in text('alignment.txt'), 'ZIP alignment failed')
native = []
ycore = []
aar = ROOT / 'composeApp/libs/ycore-native.aar'
with zipfile.ZipFile(APK) as package, zipfile.ZipFile(aar) as source:
    names = package.namelist()
    require(len(names) == len(set(names)), 'Duplicate APK ZIP entries')
    require(package.testzip() is None, 'APK ZIP CRC failure')
    actual = {name for name in names if name.startswith('lib/') and name.endswith('.so')}
    expected = {f'lib/arm64-v8a/{name}' for name in NATIVE}
    require(actual == expected, f'Unexpected native libraries: {sorted(actual ^ expected)}')
    for name in sorted(actual):
        content = package.read(name)
        native.append({'path': name, 'bytes': len(content), 'sha256': digest(content)})
    entries = [name for name in source.namelist() if name.startswith('jni/arm64-v8a/') and name.endswith('.so')]
    require(len(entries) == 8, 'Unexpected YCore native source contents')
    for name in sorted(entries):
        content = source.read(name)
        require(package.read('lib/' + name.removeprefix('jni/')) == content, f'YCore library mismatch: {name}')
        ycore.append({'source': name, 'sha256': digest(content), 'byteIdentical': True})
data = APK.read_bytes()
report = {
    'verifiedAtUtc': datetime.now(timezone.utc).isoformat(),
    'apk': APK.name, 'applicationId': 'com.yfuse', 'versionName': '1.0.50', 'versionCode': 212,
    'packageProfile': 'full', 'abi': 'arm64-v8a', 'bytes': len(data), 'sha256': digest(data),
    'certificateSha256': CERT, 'signatureV2': 'verified', 'debuggable': False,
    'zipAlignment': '16 KB native library page alignment verified',
    'nativeLibraries': native, 'ycoreByteIdentityChecks': ycore,
    'sourceManifest': 'source-hashes.json',
    'previousRegressionEvidence': '../../runtime-optimization-20260910/RESULTS.md',
    'deviceTests': 'Not run; no device connected or operated',
}
(OUT / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
(OUT / (APK.name + '.sha256')).write_text(f'{report["sha256"]}  {APK.name}\n', encoding='utf-8')
(OUT / 'README.md').write_text(
    '# Yfuse 1.0.50 (212) 正式签名包\n\n'
    f'- 安装包：[{APK.name}]({APK.name})\n'
    '- 包名：`com.yfuse`，ARM64 完整版，非 debuggable。\n'
    f'- 大小：{len(data):,} 字节（{len(data) / 1_000_000:.2f} MB）。\n'
    f'- SHA-256：`{report["sha256"]}`。\n'
    '- 沿用项目正式证书，APK v2 签名、16 KB ZIP 对齐、ZIP CRC 校验通过。\n'
    '- 20 个预期 ARM64 原生库齐全，8 个 YCore 原生库与当前 AAR 逐字节一致。\n'
    '- 1,271 个源码和构建文件的哈希在打包前后保持一致。\n\n'
    '包含本次运行时优化、选集与弹幕图标更新以及当前工作区的 Android 修改。'
    '构建使用版本参数覆盖；没有修改会触发自动发布的 version.properties。\n\n'
    '打包前的本地回归共 2,681 项通过，图标修改后的 Android/TV 编译通过，'
    '详见 [优化记录](../../runtime-optimization-20260910/RESULTS.md)。'
    '本次另通过 release 构建及设计系统检查。没有连接手机、运行设备测试或上传发布。\n\n'
    '证据：`build.log`、`signature.txt`、`badging.txt`、`alignment.txt`、'
    '`source-hashes.json`、`verification.json`。\n', encoding='utf-8')
print(json.dumps({key: report[key] for key in ('apk', 'versionName', 'versionCode', 'bytes', 'sha256')}, indent=2))
print('PASS: production signature, identity, release mode, ARM64 libraries, YCore contents, ZIP CRC and alignment')
