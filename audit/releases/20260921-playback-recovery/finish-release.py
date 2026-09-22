"""Collect passing playback regressions and verify the actual production-signed APK."""
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
OUT = ROOT / 'artifacts/releases/playback-recovery-1.0.77-239'
metadata = json.loads((BUILD / 'outputs/apk/release/output-metadata.json').read_text(encoding='utf-8'))
assert metadata['applicationId'] == 'com.yfuse' and len(metadata['elements']) == 1
element = metadata['elements'][0]
assert element['versionName'] == '1.0.77' and element['versionCode'] == 239
subprocess.run([sys.executable, str(AUDIT / 'source-manifest.py'), 'verify'], check=True)
counts = dict(tests=0, failures=0, errors=0, skipped=0)
results = ROOT / '.gradle-tmp/kernel-fixes-build/phoneShared/build/test-results/testAndroidHostTest'
audit_results = AUDIT / 'unit-results'
audit_results.mkdir(exist_ok=True)
suites = {}
for path in results.glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    suites[suite.attrib['name']] = {key: int(suite.attrib[key]) for key in counts}
    for key in counts:
        counts[key] += int(suite.attrib[key])
    shutil.copy2(path, audit_results / path.name)
for name in ('AndroidDemuxReadAheadNodeTest', 'AndroidBufferWaitMonitorTest', 'AndroidMediaSourceFailureTest',
             'AndroidYCoreProxyCloseTest'):
    assert 'com.yfuse.core2.android.' + name in suites, name
assert 'com.yfuse.core2.network.YBufferControllerTest' in suites
assert counts['tests'] >= 862 and counts['failures'] == counts['errors'] == counts['skipped'] == 0, counts
baseline = json.loads((ROOT / 'audit/releases/20260920-update-status/source-hashes.json').read_text(encoding='utf-8'))
current = json.loads((AUDIT / 'source-hashes.json').read_text(encoding='utf-8'))
changed = sorted(name for name in baseline.keys() | current.keys() if baseline.get(name) != current.get(name))
summary = {'tests': counts, 'suites': suites, 'fullAppSuiteRerun': False,
           'changedInputsFromPreviousRelease': changed, 'deviceTested': False}
(AUDIT / 'test-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
OUT.mkdir(parents=True, exist_ok=True)
apk = OUT / 'Yfuse-1.0.77-239-full-arm64-signed.apk'
shutil.copy2(BUILD / 'outputs/apk/release' / element['outputFile'], apk)
for name in ('version.properties', 'release-notes.txt'):
    shutil.copy2(ROOT / name, OUT / name)
subprocess.run([sys.executable, str(AUDIT / 'verify-release.py')], check=True)
for name in ('source-hashes.json', 'test-summary.json'):
    shutil.copy2(AUDIT / name, OUT / name)
shutil.copy2(AUDIT / 'diagnostics-guide.md', OUT / 'diagnostics-guide.md')
shutil.copy2(BUILD / 'outputs/mapping/release/mapping.txt', OUT / 'mapping.txt')
record = json.loads((OUT / 'verification.json').read_text(encoding='utf-8'))
readme = f'''# Yfuse 1.0.77（239）正式签名包

APK：`{apk.name}`，包名 `com.yfuse`，Full ARM64 手机／平板版。
大小：{record['bytes']:,} 字节。

修复补读任务退出时遗漏后续补读、缓冲等待不观察读取错误、连续卡顿的额外缓存门槛长期保留，
以及媒体 401/403 在代理和探测之间被误报并尝试其他解码路径的问题。
新增起播阶段、探测移交、Range 等待/进展/重试、音视频各轨缓存量和门控阈值日志。
缓冲等待 10 秒后只移除额外恢复储备，仍要求基础缓存；稳定播放逐步衰减卡顿历史；
35 秒没有新数据包时中断读取并进入现有的有界恢复流程。

{counts['tests']} 项回归测试，失败 {counts['failures']}，错误 {counts['errors']}，跳过 {counts['skipped']}。
范围：Core2、播放诊断和升级回归。Release 构建及设计系统检查通过。
实际 APK 1.0.77（239）高于前一交付包 1.0.76（238），配置与更新说明一致。
正式 v2 签名、包名、非调试属性、Full ARM64 运行库、ZIP/ELF 对齐及新增 DEX 日志标记通过验证。
证书 SHA-256：`{record['certificateSha256']}`。
APK SHA-256：`{record['sha256']}`。

未在用户的 OPPO 设备及实际服务器上复测；测试证明代码缺陷被覆盖，不能据此保证原片源已无卡顿。
重现时保持播放约两分钟，卡住后等待约 40 秒并导出诊断包，可观察新恢复事件。
仅本地打包，未安装设备、未推送代码、未发布升级清单。
'''
(OUT / 'README.md').write_text(readme, encoding='utf-8')
(AUDIT / 'delivery-summary.md').write_text(readme, encoding='utf-8')
print(str(apk))
