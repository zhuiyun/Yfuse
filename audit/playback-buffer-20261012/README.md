# 播放进度条缓冲显示验证

进度条使用主题色显示已播放部分、浅灰色显示已缓冲部分、暗灰色显示未缓冲部分。缓冲位置直接在绘制阶段读取，不等待缓冲动画；播放位置停止变化时仍可更新。替换状态引用后使用新位置和缓冲值，并提供缓冲时间的无障碍说明。既有章节分段和拖动操作保留。

- `:phoneShared:ktlintCommonMainSourceSetCheck`、`:composeApp:ktlintAndroidTestSourceSetCheck` 通过。
- 32 项单元测试通过：BufferedPositionTest（3）、PlaybackTransportStateTest（2）、PlayerControlFormattingTest（7）、PlayerControlSnapshotTest（1）、SeekFeedbackTest（3）、SeekScrubTiersTest（16）。JUnit XML 随本记录保存。
- Android 15 / API 35 的 Yfuse35 临时只读模拟器中，两项屏幕测试通过，耗时 13.363 秒。实际输出见 `android15-instrumentation.txt`。
- 新增测试检查黑、白主题色及正常、减少动画设置下的缓冲颜色对比；播放位置固定时缓冲继续更新；时长相同时替换播放与缓冲状态；旧缓冲及时消失。既有测试确认拖动取消不会跳转、松手仅提交一次跳转。
- 截图 `seek-buffered.png`、`seek-reduced.png` 展示验证控件；输入为模拟内核状态的固定时间轴样本。本次未执行各内核的真实网络播放或实体设备测试。
- 验证包实际版本为 `1.2.8` / `280`，应用标识 `com.yfuse.validation.buffertimeline`，采用包含 MDK 的配置。正式交付包另行执行包名、版本、正式签名、MDK、DEX、大小、SHA-256 和来源校验。

`validation.json` 记录通过的检查、验证包校验和及测试过的两份源文件校验和。此记录不表示已经上传或发布。
