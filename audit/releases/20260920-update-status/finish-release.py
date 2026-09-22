"""Collect fresh regression evidence and verify the signed update-status fix."""
import hashlib
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
BUILD = ROOT / '.gradle-tmp/kernel-fixes-build/composeApp/build'
OUT = ROOT / 'artifacts/releases/update-status-1.0.76-238'
metadata = json.loads((BUILD / 'outputs/apk/release/output-metadata.json').read_text(encoding='utf-8'))
assert metadata['applicationId'] == 'com.yfuse' and len(metadata['elements']) == 1
element = metadata['elements'][0]
assert element['versionName'] == '1.0.76' and element['versionCode'] == 238
subprocess.run([sys.executable, str(AUDIT / 'source-manifest.py'), 'verify'], check=True)

counts = dict(tests=0, failures=0, errors=0, skipped=0)
results = ROOT / '.gradle-tmp/kernel-fixes-build/phoneShared/build/test-results/testAndroidHostTest'
audit_results = AUDIT / 'unit-results'
audit_results.mkdir(exist_ok=True)
for path in results.glob('TEST-com.yfuse.update.*.xml'):
    suite = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(suite.attrib[key])
    shutil.copy2(path, audit_results / path.name)
assert counts == dict(tests=58, failures=0, errors=0, skipped=0), counts
baseline = json.loads((ROOT / 'audit/releases/20260920-update-check/source-hashes.json').read_text(encoding='utf-8'))
changed = sorted(name for name, digest in baseline.items()
                 if not (ROOT / name).is_file() or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest)
expected = {
    'composeApp/src/androidMain/kotlin/com/yfuse/update/AppUpdateManager.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/AppUpdateTools.android.kt',
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/update/UpdateCheckPolicyTest.kt',
    'version.properties', 'release-notes.txt',
}
assert set(changed) == expected, changed
summary = {'updateRegressionTests': counts, 'fullSuiteRerun': False,
           'changedInputsFromPreviousRelease': changed, 'deviceTested': False}
(AUDIT / 'test-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
OUT.mkdir(parents=True, exist_ok=True)
apk = OUT / 'Yfuse-1.0.76-238-full-arm64-signed.apk'
shutil.copy2(BUILD / 'outputs/apk/release' / element['outputFile'], apk)
for name in ('version.properties', 'release-notes.txt'):
    shutil.copy2(ROOT / name, OUT / name)
subprocess.run([sys.executable, str(AUDIT / 'verify-release.py')], check=True)
for name in ('source-hashes.json', 'test-summary.json'):
    shutil.copy2(AUDIT / name, OUT / name)
shutil.copy2(BUILD / 'outputs/mapping/release/mapping.txt', OUT / 'mapping.txt')
record = json.loads((OUT / 'verification.json').read_text(encoding='utf-8'))
readme = f'''# Yfuse 1.0.76（238）正式签名包

APK：`{apk.name}`，包名 `com.yfuse`，Full ARM64 手机／平板版。
大小：{record['bytes']:,} 字节（{record['bytes'] / 1024 / 1024:.2f} MiB）。

用户提供的 18:38 诊断包表明：1.0.75 已接受升级清单，线上版本 1.0.74 低于本机，
被错误映射成“检查失败”。本次将此成功检查结果显示为“暂无可用更新”，
保留真实连接及清单签名错误，并继续保护已有下载进度和已取得的新版本安装包。
不需要依赖服务器清单同步才能消除本次误报。

实际 APK 版本 1.0.76（238）高于上一交付包 1.0.75（237），项目配置与更新说明一致。
正式 v2 签名验证通过，证书与前一包一致，非 debuggable，可手动覆盖安装。
证书 SHA-256：`{record['certificateSha256']}`。
APK SHA-256：`{record['sha256']}`。

58 项升级测试全部通过，Release 构建及设计系统检查通过。
最终 DEX 已验证包含“暂无可用更新”、不包含旧的更新源落后错误及 RejectedNoKey 路径。
ZIP CRC、对齐、原生 ELF 16 KiB 兼容性、YCore 和 MDK Release 运行库身份检查通过。
构建前后 1,814 项输入哈希一致。详细结果见 verification.json。

仅本地打包，未安装设备、未推送代码或发布升级清单，未执行真机测试。
'''
(OUT / 'README.md').write_text(readme, encoding='utf-8')
(AUDIT / 'delivery-summary.md').write_text(readme, encoding='utf-8')
print(str(apk))
