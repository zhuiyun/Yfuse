# 文档索引

`docs/` 混放了常青指南与一次性记录。本索引是入口：**先看「常青指南」**，历史记录只在追溯某次决策或验证时使用。

时间戳（`YYYYMMDD`）出现在文件名里的文档是当时快照，其结论可能已被后续版本覆盖；引用前先核对它提到的版本号与 `version.properties`。

## 常青指南（随代码维护，应始终与实现一致）

| 文档 | 内容 |
|---|---|
| [`../README.md`](../README.md) | 构建、测试、发布入口；支持设备与 ABI |
| [`android-release.md`](android-release.md) | Android 正式发布流程 |
| [`VALIDATION_ENVIRONMENT.md`](VALIDATION_ENVIRONMENT.md) | 可复现脚本/CI 校验的环境要求 |
| [`ANDROID_PERFORMANCE_WORKFLOW.md`](ANDROID_PERFORMANCE_WORKFLOW.md) | macrobenchmark 与性能基线流程 |
| [`CLOUD_TESTING.md`](CLOUD_TESTING.md) | 云端设备测试矩阵 |
| [`watch-server-deploy.md`](watch-server-deploy.md) | 一起看中继部署与加固 |
| [`tmdb-proxy.md`](tmdb-proxy.md) | TMDB 账号代理通道 |
| [`BACKEND_ISOLATION.md`](BACKEND_ISOLATION.md) | 后端隔离边界 |
| [`playback-remote-policy.md`](playback-remote-policy.md) | 播放与遥控策略 |
| [`JS_WIDGETS.md`](JS_WIDGETS.md) | JS 组件运行时与 API |
| [`BUNDLED_WIDGETS.md`](BUNDLED_WIDGETS.md) | 内置组件的构建与恢复 |
| [`YCORE2_ARCHITECTURE.md`](YCORE2_ARCHITECTURE.md) | 当前播放内核架构 |
| [`YCORE_ARCHITECTURE.md`](YCORE_ARCHITECTURE.md) | 兼容内核架构（`core` 树） |
| [`YCORE_VALIDATION_MATRIX.md`](YCORE_VALIDATION_MATRIX.md) | 内核验证矩阵与门禁 |
| [`ycore-resource-bounds.md`](ycore-resource-bounds.md) | 内核资源上限 |
| [`FEATURE_INVENTORY_20261012.md`](FEATURE_INVENTORY_20261012.md) | 功能清单（注：日期快照，仍被当作功能地图使用） |

## 审查记录（按时间倒序）

| 文档 | 状态 |
|---|---|
| [`TECHNICAL_REVIEW_20261029.md`](TECHNICAL_REVIEW_20261029.md) | **最新**；基线 1.7.9 / 331 |
| [`TECHNICAL_REVIEW_20261014.md`](TECHNICAL_REVIEW_20261014.md) | 已被 20261029 取代，保留用于对照「哪些上轮问题仍未修复」 |
| [`TECHNICAL_REVIEW_20261014_REVISED.md`](TECHNICAL_REVIEW_20261014_REVISED.md) | 同上，是同日修订稿；与上一份高度重复 |
| [`CODE_QUALITY_REVIEW_20260904.md`](CODE_QUALITY_REVIEW_20260904.md) | 历史 |
| [`PRODUCT_AND_CODE_REVIEW.md`](PRODUCT_AND_CODE_REVIEW.md) | 历史，无日期 |

## 迁移记录

- [`AGP9_MIGRATION_20260916.md`](AGP9_MIGRATION_20260916.md) — AGP 9 / 模块化迁移（文中的 toolchain 与 targetSdk 数字是迁移当时的取值）
- [`ANDROID17_MIGRATION_20260920.md`](ANDROID17_MIGRATION_20260920.md) — API 37 迁移与真机验证清单

## 播放内核专题

- [`PLAYER_CONVERGENCE_20260830.md`](PLAYER_CONVERGENCE_20260830.md)
- [`PLAYBACK_CHAIN_REVIEW_20260920.md`](PLAYBACK_CHAIN_REVIEW_20260920.md)
- [`PLAYBACK_FOLLOWUP_REVIEW_20260920.md`](PLAYBACK_FOLLOWUP_REVIEW_20260920.md) / [`PLAYBACK_FOLLOWUP_FIX_20260920.md`](PLAYBACK_FOLLOWUP_FIX_20260920.md)
- [`PLAYBACK_OPTIMIZATION_20260920.md`](PLAYBACK_OPTIMIZATION_20260920.md) / [`PLAYBACK_OPTIMIZATION_20261014.md`](PLAYBACK_OPTIMIZATION_20261014.md)
- [`KERNEL_FIXES_20260920.md`](KERNEL_FIXES_20260920.md) — 自标 superseded
- [`YCORE_AUDIT_FIXES_20260912.md`](YCORE_AUDIT_FIXES_20260912.md)、[`YCORE_HARDENING_20260908.md`](YCORE_HARDENING_20260908.md)、[`YCORE_GAP_REVIEW.md`](YCORE_GAP_REVIEW.md)、[`YCORE_MATURITY_GAP.md`](YCORE_MATURITY_GAP.md)、[`YCORE_KERNEL_REVIEW_20260904.md`](YCORE_KERNEL_REVIEW_20260904.md)、[`YCORE_KERNEL_COMPARISON_20260903.md`](YCORE_KERNEL_COMPARISON_20260903.md)

## 格式与画质验证快照

- [`DOLBY_VISION_P7_VALIDATION.md`](DOLBY_VISION_P7_VALIDATION.md)
- [`UHD_BLURAY_PLAYBACK.md`](UHD_BLURAY_PLAYBACK.md) / [`UHD_DOLBY_PLAYBACK_REGRESSION.md`](UHD_DOLBY_PLAYBACK_REGRESSION.md)
- [`BLURAY_MULTI_ANGLE_VALIDATION.md`](BLURAY_MULTI_ANGLE_VALIDATION.md)
- [`anime4k-jellyfin12.md`](anime4k-jellyfin12.md)
- [`ycore-22-verification-20260820.md`](ycore-22-verification-20260820.md)
- [`FRAME_RATE_OVERLAY.md`](FRAME_RATE_OVERLAY.md)

## 设计探索（结论已进入代码，文档仅存档）

- 动效：[`MOTION_PERFORMANCE_20260908.md`](MOTION_PERFORMANCE_20260908.md)、[`MOTION_THEMES_DESIGN_20260923.md`](MOTION_THEMES_DESIGN_20260923.md)、[`MOTION_INTERACTION_REVIEW_20260924.md`](MOTION_INTERACTION_REVIEW_20260924.md)、[`APP_MOTION_OPPORTUNITIES_20260908.md`](APP_MOTION_OPPORTUNITIES_20260908.md)
- 视觉：[`LIQUID_GLASS_PRESETS_20260923.md`](LIQUID_GLASS_PRESETS_20260923.md)、[`PARTICLE_LIGHT_DESIGN_20260911.md`](PARTICLE_LIGHT_DESIGN_20260911.md)、[`LOGO_CONCEPTS_20261004.md`](LOGO_CONCEPTS_20261004.md)
- 交互：[`INTERACTION_DESIGN_20260926.md`](INTERACTION_DESIGN_20260926.md)、[`UI_INTERACTION_DESIGN_REVIEW_20260925.md`](UI_INTERACTION_DESIGN_REVIEW_20260925.md)
- 其他：[`BDJ_ANDROID_FEASIBILITY.md`](BDJ_ANDROID_FEASIBILITY.md)、[`SHORT_DRAMA_SUPPORT_REVIEW_20261002.md`](SHORT_DRAMA_SUPPORT_REVIEW_20261002.md)、[`CLOUD_MERGE_REVIEW.md`](CLOUD_MERGE_REVIEW.md)、[`PRODUCT_COMPLETION_20260914.md`](PRODUCT_COMPLETION_20260914.md)、[`SOFT_FEEDBACK_20260917.md`](SOFT_FEEDBACK_20260917.md)、[`RECOMMENDATION_REFRESH_20260908.md`](RECOMMENDATION_REFRESH_20260908.md)、[`RELEASE_1.0.65.md`](RELEASE_1.0.65.md)

## 诊断记录

- [`diagnostics-20260916-fixes.md`](diagnostics-20260916-fixes.md)
- [`diagnostics-20261003-player-verifyerror.md`](diagnostics-20261003-player-verifyerror.md)
- [`diagnostics-20261010-playback-retirement.md`](diagnostics-20261010-playback-retirement.md)

## 维护约定

- 新增文档前先确认没有同主题的常青文档可更新；一次性记录请把日期写进文件名。
- 被取代的记录在首行标注 `Status: superseded by <文档>`，不要直接删除 —— 它们记录了当时的验证证据。
- 文档里引用具体版本号时，优先写「见 `version.properties` / `gradle/libs.versions.toml`」，避免与代码漂移。
