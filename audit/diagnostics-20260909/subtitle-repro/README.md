# 修复前的历史复现

本目录保存的是修改前生产映射代码及其缺陷断言，证明 PGS 的无限结束时间被误读为约 49.7 天、清屏事件被丢弃。

`run-repro.ps1` 用于复现历史缺陷，不能作为当前修复代码的通过标准。修复后的生产回归见 `../subtitle-fixed/run-regression.ps1`，真实 Android JNI 回归见源码 `AndroidPgsDisplaySetInstrumentedTest.kt`。
