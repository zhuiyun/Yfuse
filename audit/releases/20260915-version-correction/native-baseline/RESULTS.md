# 1.0.64（226）已交付原生制品基线安装

日期：2026-09-15。仅处理原生 AAR、配套 SHA256 / 来源清单及本目录审计证据；没有编译、安装到设备、播放、签名或发布。

## 下载与来源

- GitHub：`zhuiyun/Yfuse`，workflow run `34701134934`，artifact `10299938547`，名称 `signing-native-8dc1e5b22fbd74f014670bf07c1773891f719191`。
- 通过 GitHub connector 的 `github_download_workflow_artifact` 下载；Python `urllib.request.Request` 使用 `User-Agent: Mozilla/5.0`。临时下载 URL 未写入审计文件。
- ZIP 大小：23,809,469 字节。
- ZIP SHA256：`07d8eeedfa3a8feb3e4f6945105cd0f771ab29f9e918d3f7a2028bd90ba93f81`，与指定值完全一致。
- `native-build-info.json` 与已交付 226 制品中同名文件完全一致：源码提交 `8dc1e5b22fbd74f014670bf07c1773891f719191`，源码树 `2db1d65fe316120f8a452ce39af04b455446c9e6`。
- demux 源文件 SHA256 `559f134528273aa1dfb6f07c8209ebe39be988fa1ea6753f3849fb37bf475d4a`，与已提取 `baseline-source/scripts/native/ycore_demux_jni.cpp` 一致。
- 比较 34 项基线源码、依赖构建及安装验证输入：33 项逐字节一致；`install-ycore-native.sh` 仅 CRLF/LF 不同，已记录双方哈希。原脚本未修改，Git Bash `igncr` 处理该换行差异。
- 三个 AAR 的 SHA256 sidecar、ZIP CRC、条目唯一性及合并的 12 个库哈希全部通过；12 个库逐一与实际 `previous-1.0.64-226/Yfuse-226-1.0.64-signed.apk` 中对应 ARM64 库比较，全部相同。

来源和逐文件哈希见 [input-verification.json](input-verification.json)。这证明采用的字节与已交付制品一致；没有声称本地重现过 native 编译或再次执行真实播放。

## 原仓库门禁与安装

执行顺序：

1. `verify-inputs.py` 严格检查下载哈希、基线输入和已交付 APK；将原安装的九个 AAR / sidecar / 来源文件备份至 `previous-installed/`。
2. `install-verified-native.sh` 调用仓库未改动的 `scripts/install-yfuse-mpv-bluray.sh` 和 `scripts/install-ycore-native.sh`，使用现有 Git Bash、NDK 29 LLVM 工具与 JDK 21。没有安装新工具。
3. 原脚本的 Blu-ray、Dolby RPU/FEL、JNI / 运行时符号、YCore 依赖闭包、软件解码 / 字幕 / 光盘 / GPU 能力标记及所有 `PT_LOAD >= 16 KiB` 检查全部通过，退出码为 0。日志：[install.log](install.log)。
4. 原安装脚本校验 carrier 与 companion 的 GPU 字节相同，再从 `libmpv-release.aar` 中移除该重复条目；`ycore-gpu.aar` 负责包装一次该库。其余 ZIP 条目内容逐一保持不变，未用 `pickFirst` 掩盖差异。
5. `verify-installed.py` 再检查安装后的 sidecar、来源清单、AAR 完整性、GPU 拆分边界和 12 个已交付库哈希。结果：[installed-verification.json](installed-verification.json)。

两个安装命令只改 `composeApp/libs` 中三个 AAR 及其六个配套文件。验证临时文件局限在本目录 `tmp/`；删除前检查解析后的绝对路径。验证结束后 `tmp/` 为空，原文件备份有意保留。

## 安装结果

| 项目文件 | SHA256 |
| --- | --- |
| `composeApp/libs/libmpv-release.aar` | `db5f9f2f81d42457e2c6dfa84c6645ee4e140473c8a862a85699aaa02522c75a` |
| `composeApp/libs/ycore-native.aar` | `c78976de5c06bf637bc452b2acbfb8c8375822f4b14e1ecf41bd7893d33ce5a7` |
| `composeApp/libs/ycore-gpu.aar` | `8ff27ee0d58fcc553d6ea26818daeaf4d42caa323aeb52f2b3d172039a42562e` |

安装后的 `ycore-native.aar`、`ycore-gpu.aar` 与下载文件完全相同。原始兼容 carrier SHA256 为 `b165133792bdc184503d1c73d753b7712a68a28f8109217b193bb501b8ea0c3d`；拆出 GPU 后的 SHA256 如上。

## 全局 pin 交接

主任务负责按签名 workflow 的同一规则同步以下两项，子任务不修改 `scripts/engine-checksums.sha256`：

```text
db5f9f2f81d42457e2c6dfa84c6645ee4e140473c8a862a85699aaa02522c75a  libmpv-release.aar
db5f9f2f81d42457e2c6dfa84c6645ee4e140473c8a862a85699aaa02522c75a  libmpv-dolby-release.aar
```

`mdk-sdk-android.7z` 实际哈希仍为 `87aa236840134fe3b5c3b08e813c2b4f0557d4c2fc7034679417bc598dd785ba`，与现有 pin 相同；未修改 MDK SDK。`libmpv-dolby-release.aar` / `libmpv-stable-release.aar` 是下载选择的别名，并非本地另装的两个 AAR；未据缺少这些别名文件推断运行时缺库。

库与源码已经冻结。最终 1.0.65（227）的编译、APK 字节/版本/证书核验由主任务继续执行。
