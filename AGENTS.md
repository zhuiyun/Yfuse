# 项目操作要求

## 打包版本与更新说明

用户要求：每次打包都必须更新项目版本配置和更新说明。

- 打包前核对上一次实际交付 APK 的 `versionName` 和 `versionCode`，结合用户确认的版本及 `audit/releases`、`artifacts/releases` 中的打包记录。不能只依据可能落后的 `version.properties`，也不能只看文件名。
- 每次新的打包交付都递增版本号和内部版本号，并在构建前同步写入根目录 `version.properties` 与 `release-notes.txt`。用户指定版本时遵循指定值；发现其不高于上一包时先说明冲突。
- `release-notes.txt` 首行必须等于新的 `VERSION_NAME`，其后记录本次实际改动，保留已有历史说明。
- 不得仅通过 `-PyfuseVersionName`、`-PyfuseVersionCode` 覆盖参数出包而让项目版本配置和更新说明停留在旧版本。同一次交付的失败重试、编译修复和验证重跑沿用已更新的版本，不重复递增。
- 构建完成后读取最终 APK 的实际包名、`versionName`、`versionCode`，确认与配置及更新说明一致，且高于上一次交付包；同时验证正式签名。只有验证通过才能交付并报告版本。
- 本地打包请求不等于授权推送、发布或上传。
