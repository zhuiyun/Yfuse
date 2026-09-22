from pathlib import Path
import json
import xml.etree.ElementTree as ET
import subprocess
import sys
A = Path(__file__).resolve().parent
R = A.parents[2]
O = R / 'artifacts/releases/merged-1.0.68-230'
suites = [ET.parse(p).getroot() for p in (R/'phoneShared/build/test-results/testAndroidHostTest').glob('TEST-*.xml')]
counts = {key:sum(int(s.get(key,0)) for s in suites) for key in ('tests','failures','errors','skipped')}
assert counts['tests'] >= 2629 and counts['failures'] == counts['errors'] == counts['skipped'] == 0, counts
summary = dict(counts, module='phoneShared', task='testAndroidHostTest', sourceRevision=subprocess.check_output(['git','-c',f'safe.directory={R.as_posix()}','rev-parse','HEAD'],cwd=R,text=True).strip())
(A/'test-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
subprocess.run([sys.executable,str(A/'verify-release.py')],cwd=R,check=True)
v = json.loads((A/'verification.json').read_text(encoding='utf-8'))
for name in ('source-hashes.json','test-summary.json','native-preflight.json'):
    (O/name).write_bytes((A/name).read_bytes())
text = f"""# Yfuse {v['versionName']}（{v['versionCode']}）正式签名包

- APK：{v['apk']}
- 包名：{v['applicationId']}；手机／平板 Full ARM64，包含 YCore、MPV 与 MDK。
- 大小：{v['bytes']:,} 字节。
- SHA256：`{v['sha256']}`。
- 正式证书 SHA256：`{v['certificateSha256']}`，与上一实际交付包一致。

## 本次改动

合并云端 Anime4K、Jellyfin 12 兼容、播放探测与预缓存、跨服务器会话、播放记录同步修复，并精简搜索筛选入口。完整更新记录见 release-notes.txt。

## 验证

- 读取上一份 APK，确认从 1.0.67（229）递增为 1.0.68（230），项目版本配置与更新说明一致。
- 正式 Release 构建及内置签名、原生运行库、MDK 权利声明、GPU 和设计系统校验通过，未排除校验任务。
- 本次手机端 {counts['tests']:,} 项 JVM 测试全部通过。
- 实际 APK 的生产签名、版本、应用入口、ARM64 库集合、ZIP CRC 与 ZIP／ELF 16 KB 对齐验证通过。
- 播放器原生库与上一份交付包一致；构建前后 {v['sourceFileCount']} 份源码和构建输入哈希一致。
- compileSdk 37，targetSdk 36，minSdk 26。
- 未执行安装或真机播放验证，未发布 APK。

机器验证记录见 verification.json、signature.txt、badging.txt、manifest.txt、alignment.txt 和 SHA256.txt。
"""
(O/'README.md').write_text(text,encoding='utf-8')
(A/'README.md').write_text(text,encoding='utf-8')
print('Release verified:',O/v['apk'])
