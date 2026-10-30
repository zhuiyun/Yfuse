# 播放手势、诊断与页面转场修复

基线：远端 master `398b21fd17887e3c9a9179468b10c860ad9a4bc8`。
诊断包记录的 1.8.2 (334) / `63bb12a10fd39821409a6e7f3d962416d217412c` 无法从当前远端取得，因此不能认定本分支与该 APK 的全部源码一致。

## 修改

- 画面左、中、右长按统一临时倍速，删除每 500ms 连续 seek 的实现。保留倍速开关、松手/取消恢复、换片/投屏失效保护和锁屏限制；控制按钮各自的长按菜单不受影响。
- 横向拖动采用固定起点和距离映射，整屏最多 90 秒。拖动只预览，松手提交一次；返回原点不提交，越界后回拖可以回到原位置；预览差值不再随播放时钟漂移。
- YCore 适配层补齐 `bufferedDurationMs`，使用实际 bufferedPosition-position 的非负差值。源缓冲覆盖和可播放缓冲分别展示；seek 等待不应误计为重缓冲事件，因此未强行增加 rebuffer 计数。
- 时间线保留具体错误文本和错误类别，错误文本变化也会记录；同时记录缓冲时长，便于恢复后复查。
- ZIP 先在缓存目录完成，再校验中央目录、条目大小及 CRC，最后写入目的文件。最新日志优先。无法保证文件传输或目的存储不截断，但失败不会被报告为导出成功。
- 日历查询取得并发许可后才开始 8 秒网络超时；排队仍可取消，已完成结果继续增量显示。
- 同一进程中服务器登录身份或活动地址变化后，清除该服务器旧冷却并恢复其持久化待同步任务；其他服务器退避不变。上游实际 401/Cloudflare 拦截仍需有效凭据或服务器侧处理。
- 公共导航保持被覆盖页面不透明直至切换结束，搜索关闭保持目的页面可见；减少动画优先于电影模式转场。
- 页面切换期间暂停装饰时钟和额外内容入场，离开时将未完成入场归位；系统关闭动画时播放器独立窗口不再主动设置淡入淡出。

## 页面检查范围

代码检查：App 根导航、HomeTabScreen、LibraryScreen、SearchScreen、ServersTabHost、ProfileTabScreen/ProfileScreen、FileBrowserScreen、DeviceHandoffScreen 共用 OfficialNavDisplay；详情返回通过该宿主和 ZoomBack；另检查 PlayerActivity/PlayerActivityMotion 的打开、关闭和延迟释放路径，以及 BackOverlay 的返回取消/完成路径。

这不是逐页真机验收。弹层、横竖屏切换、预测返回中断、三种动画主题、120Hz/60Hz、返回列表保留位置和连续进入播放等场景仍需 Android 设备回归。

## 验证

- `run-host.py` 编译完整生产逻辑文件，运行 35 项 JVM 测试：区域长按策略、拖动边界、seek 合并与会话失效、临时倍速恢复、日历排队/超时/取消、持久化同步队列恢复、诊断 ZIP 完整性。
- 显式 fixture 仅替代 Settings/个人配置/日志和 Compose 状态存储；不模拟 Compose 渲染，也不替代被测手势命令和同步队列实现。依赖目录通过 `YFUSE_TEST_DEPS` 指定，使用 Kotlin 2.4.21 和 JUnit 4.13.2。
- 新增 YPlayerVideoEngineAdapter 缓冲映射回归和更新手势帮助测试，尚未在完整 Android 测试任务中执行。
- 已对变更 Kotlin 文件执行 ktlint，并运行 `git diff --check`。
- `:composeApp:compileDebugKotlinAndroid --offline --max-workers=1` 在获取 Gradle 9.8.2 时失败：`Network is unreachable`；本环境没有完整 Android 编译结果、APK 或真机帧率结果。
- 后续签名打包请求已更新为 1.8.3 (335)，并按既有授权提交云端仅打包流程；不发布线上更新。完整 Android 编译、质量门禁和签名产物校验以本提交对应的 Actions 结果为准。
- PlayerGestureState 回归使用固定距离的实际落点，并覆盖越界后回拖到原点时松手不误 seek。
