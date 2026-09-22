# Android 17 升级验证（2026-09-20）

## 修改

- 手机、TV、性能测试模块 targetSdk = 37；compileSdk = 37.0，最低版本保持原配置。
- 手机/TV 清单声明 ACCESS_LOCAL_NETWORK，统一分版本权限选择、发现/投屏请求与权限检查界面。
- 增加手动连接、Quick Connect、扫描结果和 Plex 选择的授权处理；拒绝时保留公网连接，并为已知内网显示可操作错误。
- 已保存服务器的升级提示可跳过；同一次拒绝不会在手动连接后再次触发说明。
- Gradle 定向对齐五个模块中残留的 Ktor 3.0.3 锁记录到版本目录已要求的 3.1.0，未修改依赖版本目录。

## 最终结果

- verify.ps1：BUILD SUCCESSFUL（2m 45s），手机/TV 主代码编译、清单合并、设计系统检查、两端 Lint 通过。
- 手机相关测试 126 项，TV 测试 63 项；0 失败、0 错误、0 跳过。
- 实际合并清单：手机与 TV 均 targetSdk 37 / minSdk 26，均含新的局域网权限。
- Android Lint：0 错误；手机 1、TV 6 个警告。警告涉及固定横屏声明、TV Wi-Fi/旧系统属性、ARM64 范围和图标形状；未增加基线或用全局抑制隐藏问题。
- 格式：20 个本轮相关 Kotlin/KTS 文件通过；git diff --check 通过。
- summarize.py 已核对上述结果并生成 verification.json；合并清单也保留于本目录。

## 过程记录

verify-first-attempt.log 记录 TV Ktor 锁冲突；verify-second-attempt.log 记录手机 Lint 测试配置的同类冲突。resolve-tv-locks 与 resolve-phone-locks 日志记录 Gradle 定向更新。首次尝试解析全部配置因离线缺少设备测试工具包失败，随后将范围收窄到使用 Ktor 的已锁配置，仅解析依赖图，不下载无关工具。

未连接设备，未声称完成 Android 17 真机兼容认证。真机授权、LAN/DNS、投屏、PiP、多窗口和内存压力验证清单见 docs/ANDROID17_MIGRATION_20260920.md。

本轮没有打包、安装、上传或发布；版本号保持上次交付的 1.0.73（235），下次签名打包必须按 AGENTS.md 递增。
