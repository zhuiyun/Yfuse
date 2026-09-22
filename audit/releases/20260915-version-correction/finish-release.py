from pathlib import Path
import json

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
OUT = ROOT / 'artifacts/releases/ambient-family-1.0.65-227'
result = json.loads((AUDIT / 'verification.json').read_text(encoding='utf-8'))
assert result['versionName'] == '1.0.65' and result['versionCode'] == 227
assert result['signatureVerified'] and result['previousVersionCode'] == 226
tests = result['tests']
native = json.loads((AUDIT / 'native-baseline/installed-verification.json').read_text(encoding='utf-8'))
assert native['matchesDelivered226Libraries']
report = f'''# Yfuse 1.0.65（227）正式签名包

## 版本纠正

上次实际交付包为 **1.0.64（226）**。已通过 GitHub 签名构建 run `34701134934` 下载实际 APK，读取包内元数据并验证正式证书；源码基线为 `8dc1e5b22fbd74f014670bf07c1773891f719191`。

此前本地包 `ambient-family-1.0.56-218` 使用过时基线，现已撤回并保留审计记录。本轮改动已归入 1.0.65。根 `version.properties` 与 `release-notes.txt` 均在构建前同步更新，保留 1.0.64 及之前的更新历史。未通过 Gradle 版本覆盖参数出包。

## 交付

- APK：`{result['apk']}`
- 应用 ID：`{result['applicationId']}`；版本：**1.0.65（227）**，高于实际上一包 1.0.64（226）。
- 类型：手机／平板 Full ARM64，包含 YCore、MPV 与 MDK；不含独立 TV APK。
- 大小：{result['bytes']:,} 字节。
- SHA256：`{result['sha256']}`。
- 正式证书 SHA256：`{result['certificateSha256']}`，与上一实际交付包相同。

## 本次内容

- 保留已交付版本的左上关闭按钮缩小收圆动画、仅上下氛围光、粒子样式和 YCore 稳定性修复。
- 合入本轮内嵌黑边慢速探测、取色取消恢复，以及 Android 14 及以上 HDR 取色门控修复。
- 保留家庭资料、个人片库、同步与权限、字幕、下载、设备接力、Trakt 接入和一起看多设备等本轮改动。
- 三方合并保留当前工作；氛围光策略和依赖检查脚本的三处冲突已处理。原生库从匹配的已交付构建恢复，12 个对应库与实际 226 APK 逐字节一致。

完整改动见同目录 `release-notes.txt`。设备接力、Trakt 和部分一起看改动仍需配套服务端更新；本次没有部署服务端。Trakt 开发者应用尚未注册，接入代码已完成，启用仍需服务端凭据。

## 验证

- 客户端、TV、服务端与协议：共 **{tests['total']} 项测试通过**，无失败、错误或跳过。
- 五个模块的 ktlint 检查与 58 项 Python 脚本检查通过。
- Release 构建、设计系统、版本、正式签名、完整播放运行时、MDK 权利声明和生产 GPU 门禁通过。
- 最终 APK：包名／版本／更新说明一致，v2 正式签名、ZIP CRC、ARM64 库集合、YCore 字节和 ZIP／ELF 16 KB 对齐通过。
- 构建前后 {result['sourceFileCount']} 项源码与构建输入哈希一致。
- 按用户要求未播放，也未安装到设备、上传、推送或发布。

完整机器证据：`verification.json`、`signature.txt`、`badging.txt`、`alignment.txt`、`SHA256.txt`。本地构建与合并记录位于 `audit/releases/20260915-version-correction`。
'''
(OUT / 'README.md').write_text(report, encoding='utf-8')
(AUDIT / 'README.md').write_text(report, encoding='utf-8')
for name in ('source-hashes.json', 'test-summary.json'):
    (OUT / name).write_bytes((AUDIT / name).read_bytes())
print(f'Release evidence finalized: {OUT}')
