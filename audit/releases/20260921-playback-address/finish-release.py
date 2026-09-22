"""Verify and collect the locally signed playback-address repair package."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
BUILD = ROOT / '.gradle-tmp/kernel-fixes-build/composeApp/build'
OUT = ROOT / 'artifacts/releases/playback-address-1.0.78-240'
metadata = json.loads((BUILD / 'outputs/apk/release/output-metadata.json').read_text(encoding='utf-8'))
assert metadata['applicationId'] == 'com.yfuse' and len(metadata['elements']) == 1
element = metadata['elements'][0]
assert element['versionName'] == '1.0.78' and element['versionCode'] == 240
subprocess.run([sys.executable, str(AUDIT / 'source-manifest.py'), 'verify'], check=True)
suites = json.loads((AUDIT / 'test-summary.json').read_text(encoding='utf-8-sig'))
assert sum(s['tests'] for s in suites) == 62
assert all(s['failures'] == s['errors'] == s['skipped'] == 0 for s in suites)
baseline = json.loads((ROOT / 'audit/releases/20260921-playback-recovery/source-hashes.json').read_text(encoding='utf-8'))
current = json.loads((AUDIT / 'source-hashes.json').read_text(encoding='utf-8'))
changed = sorted(name for name in baseline.keys() | current.keys() if baseline.get(name) != current.get(name))
(AUDIT / 'changed-inputs.json').write_text(json.dumps(changed, indent=2) + '\n', encoding='utf-8')
OUT.mkdir(parents=True, exist_ok=True)
apk = OUT / 'Yfuse-1.0.78-240-full-arm64-signed.apk'
shutil.copy2(BUILD / 'outputs/apk/release' / element['outputFile'], apk)
for name in ('version.properties', 'release-notes.txt'):
    shutil.copy2(ROOT / name, OUT / name)
subprocess.run([sys.executable, str(AUDIT / 'verify-release.py')], check=True)
for name in ('source-hashes.json', 'test-summary.json', 'changed-inputs.json'):
    shutil.copy2(AUDIT / name, OUT / name)
shutil.copy2(BUILD / 'outputs/mapping/release/mapping.txt', OUT / 'mapping.txt')
record = json.loads((OUT / 'verification.json').read_text(encoding='utf-8'))
readme = f'''# Yfuse 1.0.78（240）正式签名包

APK：`{apk.name}`，包名 `com.yfuse`，Full ARM64 手机／平板版。
大小：{record['bytes']:,} 字节。

直播放路保留服务器明确指定 static=true 的原片地址及路由参数，继续保留杜比原片和光盘选择策略。
重试或切换版本时保留跨源主播放地址，避免追加会话参数破坏签名链接。

62 项针对性回归测试全部通过；测试后的业务源码哈希与构建输入一致。
Release 构建、设计系统检查、最终 APK 实际包名和版本、非调试属性、正式签名、
Full ARM64 运行库、ZIP/ELF 对齐、DEX 中修复标记及构建前后源码一致性均验证通过。
实际版本 1.0.78（240）高于上一交付 APK 的 1.0.77（239）；版本配置和更新说明一致。
证书 SHA-256：`{record['certificateSha256']}`。
APK SHA-256：`{record['sha256']}`。

本次仅本地签名打包，没有安装设备、推送代码或发布升级清单。
尚未在用户服务器和 OPPO 设备上复测，不能据此保证诊断中的 403 已消失。
请用原先失败的同一影片验证；若仍失败，导出新版诊断包继续核查请求差异。
'''
(OUT / 'README.md').write_text(readme, encoding='utf-8')
(AUDIT / 'delivery-summary.md').write_text(readme, encoding='utf-8')
print(str(apk))
