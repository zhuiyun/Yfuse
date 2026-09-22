"""Collect the locally signed APK and verify it before presenting the delivery."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
BUILD = ROOT / '.gradle-tmp/kernel-fixes-build/composeApp/build'
OUT = ROOT / 'artifacts/releases/update-check-1.0.75-237'
metadata = json.loads((BUILD / 'outputs/apk/release/output-metadata.json').read_text(encoding='utf-8'))
assert metadata['applicationId'] == 'com.yfuse'
assert len(metadata['elements']) == 1
element = metadata['elements'][0]
assert element['versionName'] == '1.0.75' and element['versionCode'] == 237
subprocess.run([sys.executable, str(AUDIT / 'source-manifest.py'), 'verify'], check=True)
OUT.mkdir(parents=True, exist_ok=True)
apk = OUT / 'Yfuse-1.0.75-237-full-arm64-signed.apk'
shutil.copy2(BUILD / 'outputs/apk/release' / element['outputFile'], apk)
for name in ('version.properties', 'release-notes.txt'):
    shutil.copy2(ROOT / name, OUT / name)
subprocess.run([sys.executable, str(AUDIT / 'verify-release.py')], check=True)
for name in ('source-hashes.json', 'test-summary.json'):
    shutil.copy2(AUDIT / name, OUT / name)
mapping = BUILD / 'outputs/mapping/release/mapping.txt'
assert mapping.is_file()
shutil.copy2(mapping, OUT / 'mapping.txt')
record = json.loads((OUT / 'verification.json').read_text(encoding='utf-8'))
readme = f'''# Yfuse 1.0.75（237）正式签名包

APK：`{apk.name}`，包名 `com.yfuse`，Full ARM64 手机／平板版。
大小：{record['bytes']:,} 字节（{record['bytes'] / 1024 / 1024:.2f} MiB）。

修复正式版缺少可选升级清单公钥时错误拒绝升级检查的问题。
清单配置公钥时仍严格验证签名，保留下载完整性、APK 包名、版本及正式签名校验。
该修复需要在受影响的旧版本上手动覆盖安装一次；无需卸载原应用。

实际读取 APK 确认版本为 1.0.75（237），高于上一实际交付包 1.0.74（236）。
项目版本、更新说明与 APK 一致；正式 v2 签名验证通过，与上一包证书一致，非 debuggable。
证书 SHA-256：`{record['certificateSha256']}`。
APK SHA-256：`{record['sha256']}`。

验证：升级模块 57 项测试全部通过；Android 编译、修改文件格式检查、Release 构建通过。
最终 APK 已确认移除错误的 RejectedNoKey 路径，并保留无公钥升级路径。
ZIP CRC、zipalign、原生 ELF 16 KiB 对齐、YCore 与 MDK Release 运行库校验通过。
构建前后 1,814 项源码及构建输入哈希一致。完整证据见 `verification.json`。

本次仅本地打包，未安装至设备、未推送或上传发布，未进行真机验证。
线上清单同步至本版本前，若仍发布 1.0.74，手动检查会提示更新源版本落后；这是已有版本比较策略，
与本次已修复的公钥缺失拒绝不同。
'''
(OUT / 'README.md').write_text(readme, encoding='utf-8')
(AUDIT / 'delivery-summary.md').write_text(readme, encoding='utf-8')
print(str(apk))
