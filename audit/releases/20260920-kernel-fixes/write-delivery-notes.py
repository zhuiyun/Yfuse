import json
from pathlib import Path

audit = Path(__file__).resolve().parent
root = audit.parents[2]
out = root / 'artifacts/releases/kernel-fixes-1.0.74-236'
result = json.loads((out / 'verification.json').read_text(encoding='utf-8'))
changed_native = [Path(entry['path']).name for entry in result['previousNativeLibraryComparison']
                  if entry['status'] != 'unchanged']
text = f'''# Yfuse {result["versionName"]}（{result["versionCode"]}）正式签名包

- APK：`{result["apk"]}`
- 包名：`{result["applicationId"]}`
- 包型：手机／平板 Full ARM64（YCore、系统内核、MPV、MDK）；不包含独立 TV 安装包。
- 大小：{result["bytes"]:,} 字节（{result["bytes"] / 1024 / 1024:.2f} MiB）。
- Android：minSdk {result["minSdk"]}，targetSdk / compileSdk {result["targetSdk"]}。
- SHA-256：`{result["sha256"]}`
- 正式证书 SHA-256：`{result["certificateSha256"]}`

包含内核修复以及新增“显示帧率”开关。默认关闭，在高级播放设置或播放器“更多 → 播放设置 → 画面”中启用；主页显示页面 FPS，播放器同时显示页面、实时输出、片源 FPS。MPV／MDK 无可靠输出计数时显示“暂无数据”。页面 FPS 包含统计层绘制，不等于屏幕刷新率。

本次是在暂停后继续同一次交付，沿用已递增的 1.0.74（236）。已读取本 APK 的实际版本、包名并核对项目配置及更新说明；高于前一实际交付包 1.0.73（235），正式证书一致，v2 签名验证通过，非 debuggable。

验证通过：Release 构建、ZIP CRC、zipalign、全部原生 ELF PT_LOAD 16 KiB 兼容性、YCore AAR 与本包逐字节一致性、读取中断 JNI 标记、本次 Release MDK JNI 一致性、APK 内帧率功能标记和实际 HTTP 策略。压缩 .so 安装时解压，其 ZIP 数据偏移不适用 16 KiB 要求；不将 zipalign 通过误报为所有压缩 .so 偏移均对齐。

相对上一包发生变化的原生库：{', '.join(changed_native)}。

当前代码与此前通过的 1,541 项测试输入一致：手机 2,871 项、TV 63 项测试均零失败、零错误、零跳过；手机／TV Debug Kotlin、Lint、设计系统检查通过。本次构建前后 1,814 项源码与构建文件哈希一致。细节见 `verification.json`、`test-summary.json`、`source-hashes.json` 和 `audit/releases/20260920-kernel-fixes/build.log`。

未安装到设备、未验证真机播放或 FPS 准确性，未推送或上传发布。当前项目仍未配置更新清单公钥；本 APK 的正式证书签名已独立验证，更新清单签名配置不应与 APK 签名混淆。
'''
(out / 'README.md').write_text(text, encoding='utf-8')
(audit / 'delivery-summary.md').write_text(text, encoding='utf-8')
print(json.dumps({'delivery': str(out), 'bytes': result['bytes'], 'changedNative': changed_native}, ensure_ascii=False))
