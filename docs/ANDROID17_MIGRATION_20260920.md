# Android 17 / API 37 迁移

本次将手机、TV 和性能测试模块的 targetSdk 从 36 升为 37，compileSdk 仍为 37.0，最低系统版本不变。此前 AGP 9 迁移只提高编译 SDK；本次启用 Android 17 的目标版本行为。

验证发现部分 TV 运行时及手机 Lint/测试配置仍锁定 Ktor 3.0.3，与已有版本目录的 3.1.0 冲突。本次使用 Gradle 定向更新 io.ktor 锁记录，保持既有依赖版本配置，覆盖手机、TV、共享模块和性能测试模块。

## 局域网访问

- 手机和 TV 清单声明 ACCESS_LOCAL_NETWORK。运行时按系统版本选择：37 及以上请求该权限，36 保留 NEARBY_WIFI_DEVICES 测试桥接，26–35 不申请无关的附近设备权限。
- 扫描、投屏、权限设置共用同一个权限选择器。手动登录、Quick Connect、选择扫描结果和 Plex 服务器也有连接前授权处理。
- 已有服务器的用户会获得可跳过的说明，授权后重新检测服务器。拒绝授权时明确指出局域网连接受限，并保留互联网连接能力。
- 域名也可能解析到内网，因此连接操作会提供授权机会。拒绝时，已知私网 IP、局域网名称会显示权限错误；普通域名允许继续交给系统网络栈连接，避免把互联网服务器误拦截。
- 回环地址不按局域网拦截。本项目播放 HTTP 代理运行在同一应用配置文件内；不承诺个人/工作配置文件跨域回环通信。

## 其余行为核查

| 项目 | 代码核查结果 |
| --- | --- |
| 大屏、旋转、多窗口 | 播放器在非 TV 的 600dp+ 设备使用 FULL_USER；未使用大屏限制的兼容退出开关。仍需平板/折叠屏交互验证。 |
| 后台音频 | 已有 mediaPlayback 前台服务，在可见播放时启动，并保留缓冲/短暂打断期间的服务。现有产品策略为退到隐藏后台/息屏暂停、可见 PiP 继续；本次不增加息屏播放功能。 |
| 原生动态加载 | 可见源码使用 System.loadLibrary 加载随包库，未发现从可写路径 System.load 的流程；第三方二进制行为仍需设备验证。 |
| MessageQueue、static final | 应用源码未发现私有 MessageQueue 反射或修改 static final 的路径；依赖的实际运行仍需回归。 |
| 网络安全 | 已使用 Network Security Configuration；保留 Android 17 默认 CT/ECH 行为，不全局关闭证书验证或新增信任所有证书逻辑。需实测家庭服务器及代理环境。 |
| 内存限制 | 已有图像缓存预算、播放缓冲预算和 onTrimMemory 清理；静态检查不能替代设备内存压力测试。 |
| IME | 主页面/播放器已处理配置变化和窗口 Insets；旋转后的输入焦点、键盘与弹窗可达性仍需设备验证。 |
| 不适用项 | 本次未发现短信读取、联系人 CP2 查询、桌面 RemoteViews 小组件或 RFCOMM 读取代码；无需为了这些条目增加权限。 |

## 设备验证清单

自动化验证已完成：手机 126 项、TV 63 项测试全部通过；两端 Debug 主代码编译、合并清单及设计系统检查通过。合并清单均为 targetSdk 37 / minSdk 26，并含 ACCESS_LOCAL_NETWORK。两端 Android Lint 为 0 个错误、7 个警告（横屏声明、TV Wi-Fi/旧系统属性、ARM64 范围和图标形状）。未连接真机，以下项目尚未实测。

1. Android 17 新装和覆盖安装：权限首次授权、拒绝、永久拒绝、设置中撤销、重新允许。
2. 手动私网 IP、普通域名解析私网、.local、IPv6、Plex、Quick Connect；验证公网服务器在拒绝权限后仍可连接。
3. 已保存服务器恢复、LAN 扫描、Google Cast/DLNA、直接播放和本地下载；拒绝权限后的提示与重试。
4. 播放中返回桌面/PiP、关闭 PiP、旋转、熄屏、音频焦点丢失和恢复；检查 AudioHardening 日志。
5. 600dp+ 平板/折叠屏/桌面窗口缩放，尤其添加服务器、键盘输入和播放器控件。
6. 长时间播放、海报滚动和玻璃弹窗叠加，检查内存峰值与 MemoryLimiter 退出原因。

## 官方依据

- [Android 17 目标版本行为](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Android 17 所有应用行为](https://developer.android.com/about/versions/17/behavior-changes-all)
- [局域网运行时权限](https://developer.android.com/privacy-and-security/local-network-permission)
- [后台音频限制](https://developer.android.com/about/versions/17/changes/bg-audio)

本次升级不包含新的签名 APK 交付或代码上传；版本号由下一次打包按项目规则递增。验证日志位于 audit/android17-20260920。
