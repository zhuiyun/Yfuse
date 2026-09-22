from pathlib import Path
import json
A = Path(__file__).resolve().parent
R = A.parents[2]
O = R / 'artifacts/releases/ui-settings-1.0.66-228'
v = json.loads((A/'verification.json').read_text(encoding='utf-8'))
assert v['versionName']=='1.0.66' and v['versionCode']==228 and v['signatureVerified']
assert v['previousVersionName']=='1.0.65' and v['previousVersionCode']==227
assert v['projectMetadataMatches'] and v['releaseNotesMatch'] and v['matchesPreviousDeliveredNativeLibraries']
for name in ('source-hashes.json','test-summary.json','native-preflight.json'):
 (O/name).write_bytes((A/name).read_bytes())
text = f'''# Yfuse 1.0.66（228）正式签名包

- APK：{v['apk']}
- 包名：{v['applicationId']}；版本：1.0.66（228）。
- 类型：手机／平板 Full ARM64，包含 YCore、MPV 与 MDK。
- 大小：{v['bytes']:,} 字节。
- SHA256：`{v['sha256']}`。
- 正式证书 SHA256：`{v['certificateSha256']}`，与实际上一交付包一致。

## 本次改动

恢复媒体库原布局并加入可保存的轮播开关；恢复并前移详情页资源比较；统一个人收藏、想看按钮；恢复原设置首页，将个人功能改为统一样式的二级页并合并重复入口。完整历史见 release-notes.txt。

## 构建与核验

- 实际读取并校验上一份 1.0.65（227）APK，本次两个版本号均递增。
- 构建前同步更新根 version.properties 与 release-notes.txt，未使用版本覆盖参数。
- 本地原生 AAR、sidecar、21 项原生源码及 12 个运行库已对照上一交付证据核对；修复落后的 MPV 校验值配置。
- 正式 Release 构建通过，原生包、版本、正式签名、完整运行库、MDK 权利声明、GPU 和设计系统校验全部通过，未排除校验任务。
- 最终 APK 的实际包名、版本、正式 v2 签名、不可调试状态、ZIP CRC、ARM64 库集合、原生字节及 ZIP／ELF 16 KB 对齐核验通过。
- 构建前后 {v['sourceFileCount']} 个源码／构建输入文件哈希一致。
- 本轮 UI 相关 68 项单元测试与修改文件的 ktlint 检查通过。
- 未安装或播放验证，未上传、推送或发布。

机器证据见 verification.json、native-preflight.json、signature.txt、badging.txt、alignment.txt 与 SHA256.txt。构建日志位于 audit/releases/20260916-ui-settings/build.log。
'''
(O/'README.md').write_text(text,encoding='utf-8')
(A/'README.md').write_text(text,encoding='utf-8')
print('Release evidence complete:',O)
