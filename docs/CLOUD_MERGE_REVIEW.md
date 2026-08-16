# 云端代码合并与优化审查

审查日期：2026-08-16 · 基线：`master` @ `ba9a244` · 版本：0.2.72 (153)

---

## 一、合并结论：无代码可合并

对 `origin` 上全部 **72 个远程分支**逐一做了合并模拟（`git merge-tree --write-tree origin/master <branch>`，
再把结果树与 `master` 的树逐字节比较），结论是明确的：

| 分类 | 数量 | 含义 |
| --- | --- | --- |
| 内容已完全进入 master | 16 | 合并结果树 **等于** master 的树，合并是空操作 |
| 已被 master 取代的历史分支 | 55 | 分支落后于 master，合并只会**回退**已发布的代码 |
| master 本身 | 1 | — |

也就是说，**云端所有工作都已经通过 squash 合并落到了 `master`**，当前工作分支
`claude/cloud-code-merge-optimize-dqg6oh` 与 `origin/master` 完全一致（`0 0` ahead/behind）。

### 逐分支验证（近期分支）

以下分支从提交数上看"领先" master，但内容已 100% 落地，合并后树无变化：

| 分支 | 领先提交 | master 中的对应 squash |
| --- | --- | --- |
| `feature/ycore-player` | 41 | `73bffee` + `b40e26d` YCore kernel/handovers |
| `agent/p0-engineering-hardening-20260816` | 5 | `ba9a244` ci: harden supply chain |
| `agent/hide-movie-auto-skip` | 3 | `3f24f26` 限制片头片尾跳过到剧集 (#62) |
| `agent/fix-silent-audio-fallback` | 3 | `f9ee5db` + `20d82e3` 静音回退修复 (#61) |
| `codex/player-time-digit-roll-20260815` | 2 | `c64ef16` 播放时间数字上滚 |
| `codex/player-popup-footprint-20260815` | 1 | `e337021` 减少播放器弹窗遮挡 |

`codex/merge-all-sign-20260815` 曾是候选，但双向 diff 显示它相对 master 是
**+185 / −715 行**——纯粹是落后的签名构建分支，合并会删除 YCore 与供应链脚本。

那 55 个"发散"分支中被识别为"master 没有的源文件"，全部是 master 已**主动删除或重构掉**的历史产物：
`feature/player/VideoEngine.kt`（已迁到 `player/contract/`）、
`scripts/agent_full_upgrade_phase*.py`（一次性迁移脚本）、
`tmp/danmaku-qa-*.xml`（QA 临时文件）、旧版 `launch_background` 资源。没有一个是未落地的功能。

### 建议：清理分支

72 个分支中 71 个已无价值，`git branch -r` 的可读性已经归零，也让"哪个分支是活的"无法回答。

```bash
# 第一批：内容已完全并入 master，删除零风险
for b in feature/ycore-player agent/p0-engineering-hardening-20260816 \
         agent/p0-engineering-hardening-v2 agent/hide-movie-auto-skip \
         agent/fix-silent-audio-fallback agent/release-0.2.72 \
         agent/unify-home-tab-reduce-transparency codex/player-time-digit-roll-20260815 \
         codex/player-popup-footprint-20260815 codex/watch-v6-preflight-20260815 \
         codex/sign-request-versioning-20260815 codex/sign-pr-trigger-20260815 \
         codex/player-popup-redesign-20260815 codex/player-dedicated-popups-20260815 \
         codex/fix-profile-frosted-glass-20260815; do
  git push origin --delete "$b"
done
```

第二批（55 个落后分支）建议先打一个归档 tag 再删，或直接依赖 GitHub 的 90 天 reflog。
长期做法：在 `publish-android.yml` 合并后自动删除源分支，或开启仓库的
Settings → General → "Automatically delete head branches"。

---

## 二、检查范围与限制

- 静态审查：125,572 行 Kotlin / 559 个文件，4 个 Gradle 模块，6 个 CI workflow。
- 测试规模：178 个测试文件 / 992 个 `@Test`——覆盖率是这个仓库的强项。
- **未能在本环境执行构建**：沙箱代理封禁了 `dl.google.com`（AGP 8.11.1 无法解析）
  与 `api.osv.dev`，因此 `:composeApp` 编译、Android lint、R8 打包、供应链扫描
  均未实测。本文所有结论来自源码与配置分析，标注为"待验证"的条目需要在 CI 上确认。

代码基线质量很高，值得先说明：全仓 **0 个 `!!`**、0 个 `printStackTrace`、
0 个 `TODO/FIXME`、0 个 `GlobalScope`，生产代码没有 `runBlocking`。
服务端口令用 PBKDF2-HMAC-SHA256 / 600k 迭代 + 常数时间比较 + 双层限流，
CI 的 action 全部按 SHA 钉住。下面的建议是在这个水准之上的增量。

---

## 三、优化建议

### P0-1 · 供应链扫描门禁实际上永远不会失败 —— ✅ 已修复

`scripts/supply_chain_check.py:73-77`

```python
def severity(vulnerability: dict) -> str:
    explicit = vulnerability.get("database_specific", {}).get("severity")
    if explicit:
        return str(explicit).upper()
    return "UNKNOWN"
```

严重度来自 `query_osv()` 的返回值，而它调用的是 OSV 的 **`/v1/querybatch`** 端点。
该端点按设计只返回**精简记录**——每个漏洞只有 `id` 和 `modified` 两个字段，
不含 `database_specific`、`severity`、也不含 `summary`。

后果：`severity()` 恒返回 `"UNKNOWN"`，`SEVERITIES = {"HIGH", "CRITICAL"}` 永不命中，
`main()` 的 `return 1 if high else 0` 恒为 0。**即使依赖里存在 CRITICAL 漏洞，
quality-gates 的 supply-chain job 也会绿灯通过**；`release.yml` 里的复检同理。
同时 SBOM 中每个包的 `known vulnerability at scan time` 注释虽然是对的（基于 `findings` 非空判断），
但错误提示行 `f"...({vulnerability.get('summary', 'no summary')})"` 永远打印 `no summary`。

修复：批量查询拿到 ID 后，对去重的 ID 集合再调 `GET /v1/vulns/{id}` 取完整记录，
并且不要只读 GHSA 专有的 `database_specific.severity`——同时解析标准的
`severity[]`（CVSS 向量）和 `affected[].ecosystem_specific`，取最高者：

```python
def fetch_details(ids: set[str]) -> dict[str, dict]:
    details = {}
    for vuln_id in sorted(ids):
        request = urllib.request.Request(
            f"https://api.osv.dev/v1/vulns/{vuln_id}",
            headers={"User-Agent": "Yfuse-supply-chain/1"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            details[vuln_id] = json.load(response)
    return details
```

**已实施的修复**（`scripts/supply_chain_check.py`）：

- `query_osv()` 改为只收集漏洞 ID，新增 `fetch_details()` 对去重后的 ID 逐个调用
  `GET /v1/vulns/{id}` 取完整记录。
- `severity()` 同时解析 GHSA 的 `database_specific.severity`（`MODERATE` 归一到 `MEDIUM`）
  和标准 `severity[]` 里的 CVSS 3.x 向量，取两者中最严重的一个。
  新增的 `cvss_v3_score()` 按 CVSS 3.1 规范附录 A 实现基础分计算（含 scope-changed 公式
  与浮点安全的 roundup），无第三方依赖。
- **改为 fail-closed**：无法解析出严重度的记录返回 `UNRESOLVED` 并计入阻断集合，
  而不再是无害的 `UNKNOWN`。安全门禁宁可吵，不可静默放行。
- 跳过 `withdrawn`（上游已撤回）的记录，它们不是有效发现。
- SBOM 中每个包的注释从 `yes/no` 升级为实际严重度等级。

新增 `scripts/test_supply_chain_check.py`（10 个用例，全部通过），其中
`test_an_advisory_without_severity_blocks_rather_than_passes` 直接构造 `querybatch`
返回的那种精简记录，钉住了这次的回归。CVSS 计算用 Log4Shell(10.0)、9.8、7.5、5.5、4.0、0.0
六个向量交叉验证。`quality-gates-v2.yml` 的 supply-chain job 在扫描前先跑这套测试。

以桩替换网络做的端到端验证：CRITICAL → 退出码 1（修复前是 0）、LOW → 0、withdrawn → 0。

---

### P0-2 · 发布说明可能带着上一版的内容进入新 tag —— ✅ 已修复

`.github/workflows/release.yml`

```bash
notes="release-notes.txt"
if [[ ! -s "$notes" ]]; then printf '...' > "$notes"; fi
gh release create "$tag" ... --notes-file "$notes" ...
```

回退只在文件**为空**时触发，从不检查文件内容是否**属于本次版本**。
`release-notes.txt` 是提交进仓库的，首行就是版本号（当前为 `0.2.72`）。
只要有人改了 `version.properties` 触发发布却忘了更新说明，
`v0.2.73` 这个 tag 就会挂着 0.2.72 的更新日志——而 release 又是显式设计成**不可变**的
（`refusing to mutate an existing release`），事后无法修正，只能作废版本号。

当前仓库正处在这个风险窗口里：master 上有 3 个已合并但未发布的提交
（YCore 内核、切换恢复、CI 加固），而 `release-notes.txt` 仍然只描述 0.2.72 的跳过提示改动。

**已实施的修复**（`.github/workflows/release.yml`）：

在 "Read release version" 之后加入 "Verify release notes belong to this version"，
比对 `release-notes.txt` 首行与 `VERSION_NAME`，不一致或文件为空即失败。

同时移除了原来"文件为空就生成占位说明"的回退——它会把一次本该失败的发布
悄悄变成一次内容空洞的发布。构建标识（versionCode 与 commit）改为**追加**在
真实说明之后写入 `build/release-notes-<version>.txt`，保留了原回退里的溯源信息，
但绝不再用生成文本顶替本该由人写的说明；同时不再改写工作区里被提交的文件。

对当前仓库状态实测：`0.2.72` 通过，`0.2.73` 被拒（说明文件仍停留在 0.2.72）
——正是本节描述的风险窗口。

---

### P1-1 · 七套互不共享的 HTTP 栈

Android 端目前同时存在 **7 个独立的 Ktor `HttpClient`**，每一个默认参数都调用
`embyHttpEngine()`，而 `HttpClientFactory.android.kt:12` 的实现是 `OkHttp.create()`
——每次调用都新建一个 `OkHttpClient`，也就是各自独立的连接池（5 条空闲连接 / 5 分钟保活）、
独立的 Dispatcher 线程池和独立的 TLS 会话缓存：

| 位置 | 客户端 |
| --- | --- |
| `di/AppModule.kt:106` | Emby 主 API |
| `di/AppModule.kt:133` | 弹幕 |
| `core/network/Tmdb.kt:27` | TMDB |
| `core/account/AccountApi.kt:30` | 账号服务 |
| `core/sync/WatchTogetherClient.kt:45` | 一起看 WebSocket |
| `core/migration/MigrationRelayApi.kt:77` | 迁移中继 |
| `YfuseApp.kt:113` | Coil 图片加载（未指定引擎，走 ServiceLoader 拿到 OkHttp） |

叠加另外 **3 套裸 `HttpURLConnection`**：`update/AppUpdateManager.kt`（1877 行，自带断点续传实现）、
`core/cast/CastManager.android.kt:1019`、`core/offline/OfflineMedia.android.kt:960`。

代价是实打实的：Emby 主 API 与图片加载打到**同一台服务器**，却因为分属两个连接池
而无法复用 TCP 连接和 TLS 会话——每张海报的首次加载都要多付一次握手。
线程数同理，7 个 Dispatcher 各自最多 64 线程。

建议：建一个共享的 `OkHttpClient`（统一连接池、超时、Network Security Config），
所有 Ktor 客户端通过 `OkHttp.create { preconfigured = shared }` 复用它，
`HttpURLConnection` 的三处也换成 OkHttp 的 `Call`（断点续传用 `Range` 头，语义完全一致）。
预期收益：冷启动后首屏海报加载减少一轮 TLS 握手，常驻线程与 socket 数量下降。

---

### P1-2 · 图片加载忽略用户自定义 User-Agent

`YfuseApp.kt:125-127`

```kotlin
install(io.ktor.client.plugins.UserAgent) {
    agent = com.yfuse.core.network.DEFAULT_EMBY_USER_AGENT
}
```

紧邻的注释准确地说明了为什么 UA 重要：*"很多 Emby 部署在 Nginx 反代后面，按 User-Agent
给 `/Items/{id}/Images/...` 设卡，默认的 `Ktor/x.x` 会拿到 403，图片静默加载失败"*。

但这里写死了 `DEFAULT_EMBY_USER_AGENT`，而 `createEmbyClient` 用的是
`customUserAgent()` → `UserAgentPreferences.userAgent`。用户在设置里配置自定义 UA
（这个开关存在的唯一理由就是应付挑剔的反代）之后，API 请求带新 UA、图片请求仍带旧 UA
——正好复现注释里描述的那个故障，而且只影响图片，排查起来极其隐蔽。

修复：把 `UserAgentPreferences` 注入 `newImageLoader`，与 API 客户端读同一个 `StateFlow`。
`YfuseApp` 已经是 Koin 宿主，取值不困难。

---

### P1-3 · 冷启动在主线程做了过多急切初始化

`YfuseApp.onCreate()` 顺序执行：`clearLegacyCredentialCaches` → `initializeDeviceId`
→ `DiagnosticLogStore.initialize` → `startKoin` → `AccountRepository.start()`
→ `PlaybackReportingCoordinator.flushPending()` → 急切构造 `AppUpdateManager`。

其中确实必须在首帧前完成的只有 deviceId 与 Koin。另外三项是可以推迟的：

- `flushPending()` 恢复的是上次进程死亡时未上报的播放事件——晚几百毫秒毫无影响。
- `AppUpdateManager` 的 `init`（`AppUpdateManager.kt:652`）调用 `restoreInterruptedDownload()`，
  它同步读 SharedPreferences 和 `File.isFile`。代码里已经很克制地把 APK 哈希校验挪到了
  `Dispatchers.IO`（注释写得很清楚），但构造本身仍在主线程。
- `DiagnosticLogStore.initialize(this)` 触碰文件系统。

建议：保留 deviceId 与 `startKoin`，其余三项挪到 `Dispatchers.Default` 的
application scope 里，或用 `androidx.startup` 的 `Initializer` 声明依赖顺序。
`AppUpdateManager` 改成懒解析（Koin 的 `single` 默认就是懒的，去掉 `onCreate` 里那行
`koin.get<AppUpdateManager>()` 即可），首次进入首页时再触发。

---

### P1-4 · ktlint 基线里压着 2249 条违规

```
config/ktlint/composeApp-baseline.xml        1059 条
config/ktlint/watchTogetherServer-baseline.xml 1190 条
config/ktlint/mdkAndroid-baseline.xml            0 条
config/ktlint/watchTogetherProtocol-baseline.xml  0 条
```

根 `build.gradle.kts` 的注释把机制描述得很准确——基线让**新增**违规才失败，
`ktlintGenerateBaseline` 是显式的债务重置操作、绝不在 CI 自动运行。机制没问题，
问题是债务规模：两个主模块合计 2249 条，且从提交历史看，`style(app): satisfy merged ktlint rules`
这类提交反复出现，说明开发者在与基线拉锯。

建议按模块分批清偿：`./gradlew :watchTogetherServer:ktlintFormat` 后重新生成该模块基线，
大部分是纯格式规则，`ktlintFormat` 可以自动修掉，一次 PR 一个模块，diff 大但零语义变更。
两个 0 条的模块证明这是做得到的。

---

### P2-1 · `readTarget` 无限读取局域网设备返回，且连接可能泄漏

`core/cast/CastManager.android.kt:1019-1022`

```kotlin
val connection = URL(location).openConnection() as HttpURLConnection
connection.connectTimeout = 2_000
connection.readTimeout = 2_000
val xml = connection.inputStream.bufferedReader().use { it.readText() }
connection.disconnect()
```

两个问题：

1. `readText()` 对 SSDP 发现出来的设备描述文档**没有大小上限**。一个行为异常或恶意的
   局域网设备可以持续吐字节直到 OOM——`readTimeout` 只约束单次读的间隔，不约束总量。
2. `disconnect()` 不在 `finally` 里。`readText()` 抛异常时（超时、编码错误）连接不会释放。

修复：`connection.inputStream.use { it.readNBytes(64 * 1024).decodeToString() }`，
并把 `disconnect()` 放进 `try/finally`。DLNA 设备描述文档正常只有几 KB，64 KB 上限绰绰有余。

---

### P2-2 · Emby 身份偏好表只增不减

`core/network/HttpClientFactory.kt:130` 起，`preferredClientBySession` 是一个
`MutableStateFlow<Map<EmbyIdentityPreferenceKey, String>>`，键是 `(origin, accessToken)`，
写入只有 `it + (preferenceKey to fallbackClient)`，没有任何淘汰路径。

每次 token 轮换都会留下一条永不回收的旧条目。实际增长受"服务器数 × token 轮换次数"约束，
单次会话内量级很小，属于慢泄漏而非事故。但既然客户端本身是单例、生命周期等同于进程，
建议换成有界结构（LRU，容量 16 足够），或在 token 失效时主动清理对应条目。

---

### P2-3 · TMDB token 编进 BuildConfig，随 APK 分发

`composeApp/build.gradle.kts:286`

```kotlin
buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
```

从 `local.properties`（已 gitignore）读取，确实没进 git——这一层做对了。但结果是明文常量进入
DEX，任何人反编译 APK 都能提取。这是客户端直连第三方 API 的固有代价，需要明确接受或规避。

考虑到项目**已经自建了 `watchTogetherServer`**，最干净的做法是让 TMDB 请求走自己的服务端中转，
token 只存在于服务器；退一步的做法是接受现状，但在 TMDB 后台给这个 key 配上域名/配额限制，
并在文档里写明它是公开凭据。

另外一个健壮性小问题：`"\"$tmdbToken\""` 是裸插值，若 token 含 `"` 或 `\` 会生成非法 Java 源码。
用 `"\"${tmdbToken.replace("\\", "\\\\").replace("\"", "\\\"")}\""` 更稳妥。

---

### P2-4 · CI 有三份重复的 Android 环境配置

`publish-android.yml`、`quality-gates-v2.yml`、`sign-android-branch.yml` 各自复制了同一段
~30 行的 "安装 Android 36 SDK + NDK 29 + CMake" 与 "下载并校验播放引擎" 逻辑
（三个文件都命中 `sdkmanager`，且都调用 `fetch-engines.sh` 两次）。
NDK 版本号 `29.0.14206865` 这类字符串一旦升级就要改三处，漏一处的失败会推迟到打包阶段才暴露。

建议抽成 `.github/actions/setup-android/action.yml` 复合 action，三个 workflow 各一行引用。
同时把引擎产物加进 `actions/cache`（key 用 `scripts/engine-checksums.sha256` 的哈希）——
`libmpv-release.aar` + `mdk-sdk-android.7z` 每次 CI 都从 GitHub Releases 重下，
缓存能省下每个 job 一到两分钟。

`publish-android.yml` 本身有 39,905 字节 / 25 个 step 挤在单个 job 里，也建议按
"构建 → 签名 → 校验 → 发布" 拆分成有依赖关系的多个 job，失败时能直接定位阶段，
且可重跑单个阶段而不必重跑整条流水线。

---

### P2-5 · 两个 2000 行以上的文件

| 文件 | 行数 |
| --- | --- |
| `feature/player/PlayerRoot.kt` (androidMain) | 2272 |
| `watch/Application.kt` (server) | 2218 |
| `feature/servers/ServersTabScreen.kt` | 1951 |
| `update/AppUpdateManager.kt` | 1877 |

`gradle.properties` 里那条注释本身就是这个问题的直接证据：

> PlayerActivity contains the three playback backends' orchestration and now exceeds the
> Kotlin IR compiler's reliable 1.5 GB ceiling on clean builds. 3 GB is the smallest heap
> verified by both local debug compilation and CI-like no-daemon builds.

也就是说单文件体积已经把 Kotlin 编译器的堆需求推到 3 GB，这是每个开发者每次 clean build
都在付的成本。`docs/YCORE_ARCHITECTURE.md` 已经定义了正确的边界
（"New probes or backends implement an interface instead of adding conditions to `PlayerRoot`"），
建议照着这条边界把 `PlayerRoot` 里的后端编排拆成 `commonMain` 的策略 + `androidMain` 的适配器。
服务端 `Application.kt` 同理，房间状态机、WebSocket 路由、REST 路由可以各自独立。

---

### P2-6 · Gradle 配置缓存被关闭

`gradle.properties:3` 设了 `org.gradle.configuration-cache=false`。
`composeApp/build.gradle.kts` 里有若干配置期直接读文件的写法
（`local.properties`、`keystore.properties`、`version.properties`），
以及 `verifyDesignSystemUsage` 在 `doLast` 里引用 `projectDir`——这些正是配置缓存不兼容的典型模式。

改造方向：把这些读取换成 `providers.fileContents(...)` / `providers.gradleProperty(...)`
的惰性 Provider，`verifyDesignSystemUsage` 改成带 `@InputFiles` / `@OutputFile` 的
自定义 `DefaultTask`（顺带获得增量与 up-to-date 能力——它现在**每次构建都全量重跑**，
因为只声明了 `inputs` 没声明 `outputs`）。配置缓存打开后，增量构建的配置阶段基本归零。

---

## 四、优先级建议

| 优先级 | 事项 | 理由 |
| --- | --- | --- |
| ✅ 已修复 | P0-1 供应链门禁失效 | 安全门禁静默通过，比没有门禁更危险 |
| ✅ 已修复 | P0-2 发布说明校验 | release 不可变，一旦发错无法修正 |
| 本迭代 | P1-2 图片 UA | 用户可见的功能性 bug，排查成本高 |
| 本迭代 | P1-1 HTTP 栈合并 | 直接改善首屏加载与资源占用 |
| 本迭代 | P1-3 冷启动 | 用户可感知的启动耗时 |
| 排期 | P1-4 ktlint 债务 | 持续拖慢每一个 PR |
| 排期 | P2-1 ~ P2-6 | 健壮性、安全边界与工程效率 |
| 随时 | 分支清理 | 零风险，立即改善仓库可读性 |

审查未能覆盖：`:composeApp` 的实际编译、Android lint 结果、R8 产物与 APK 体积预算
（30 MB）、以及运行时 profiling。这些需要在具备 Android SDK 与外网的环境中补齐。
