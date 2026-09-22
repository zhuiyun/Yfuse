# 已撤回：版本基线错误

已撤回：此本地包误以 1.0.55（217）为版本基线。
已核实上次实际交付包为 1.0.64（226），故本次改动改由 1.0.65（227）交付。
1.0.56（218）包仅保留用于审计，请勿作为最新版安装或分发。
原版本说明中的本轮改动已归入根 release-notes.txt 的 1.0.65，原记录保留在本目录。

---

# 2026-09-15 签名打包记录

交付：**Yfuse 1.0.56（218）ARM64 完整版**，包名 `com.yfuse`。

实读上一交付 APK 为 1.0.55（217），并重新验证其正式签名。构建前更新根目录 `version.properties` 和 `release-notes.txt`，保留历史说明；构建未使用版本覆盖参数。

Release 构建成功（5 分 7 秒），最终 APK 版本、生产签名、原生库完整性、YCore 字节来源、ZIP CRC、ZIP 及 ELF 16 KB 对齐均通过。打包前后 1,491 项源码／构建输入一致。

- [安装包与交付说明](../../../artifacts/releases/ambient-family-1.0.56-218/README.md)
- [构建脚本](build-release.ps1)
- [构建日志](build.log)
- [最终校验脚本](verify-release.py)
- [校验结果](verification.json)
- [源码与构建输入哈希](source-hashes.json)
- [氛围光修复与 2,639 项客户端回归](../../ambient-freeze-20260915/RESULTS.md)
- [此前产品改进及服务端生效条件](../../product-implementation-20260915/RESULTS.md)

未安装或播放，未部署、推送、上传或发布。
