# 同 IP 媒体服务器 HTTP 策略修复

用户媒体地址：`http://47.112.219.60:19001`。匿名公开接口 `/System/Info/Public` 实测返回 200。

## 修复

移除 `network_security_config.xml` 中对 `47.112.219.60` 的主机级 HTTP 禁用规则。Android 该配置不按端口匹配，因此原本用于账号/更新服务的规则也拦截了同 IP 的用户媒体服务。默认媒体 HTTP 兼容策略和元数据/Plex 官方域名 TLS 限制保持原状。账号构造、令牌来源和更新下载校验仍独立约束官方服务。

## 验证

- 28 项 JVM 回归测试通过：HttpClientFactoryTest 12 项、ServerEndpointPolicyTest 4 项、AccountAccessTokenSourceTest 2 项、UpdateManifestPolicyTest 10 项。
- 内部 APK 与测试 APK 编译成功。Gradle connectedDebugAndroidTest 在下载 UTP 工具依赖时因网络权限失败，未产生本次设备测试结论。
- 改用 ADB 安装同一组生成产物并执行 AndroidJUnitRunner，2 项新增实机测试全部通过，见 `device-tests.log`。验证设备 SM-G973U，Android 9 / API 28；未在用户 OPPO / Android 16 上实测。
- 实机验证共享 IP 和普通媒体地址允许 HTTP，TMDB、Trakt、Plex 官方域名仍禁止 HTTP。
- 使用 aapt2 检查生成 APK 中的真实网络配置，见 `packaged-policy.txt`。
- 内部包名 `com.yfuse.networkpolicy`，未覆盖设备上的正式应用。未对外交付新 APK，版本配置保持 1.0.69 / 231；新的正式交付需按项目规则递增版本并重新签名验证。

诊断依据见 `../library-diagnostics-20260918/`。此前接力客户端修复和服务端依赖锁变更保留，未混入本次两项源码变更。
