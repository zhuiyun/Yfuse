# 1.0.77 播放修复与诊断说明

## 已实现

- `AndroidDemuxReadAheadNode` 在填充任务退出时，于同一锁内清除运行标记并复查补读需求。
  固定时序测试在生产者退出前取空缓存，然后停止消费者轮询，验证生产者仍会再次填充。
- 增强播放处于缓冲等待时仍检查生产者失败，并确保补读任务存在。
  35 秒没有新增压缩数据包时中断读取，发布可恢复的 Network/Demux 失败，由既有重试次数限制处理。
  该期限大于 Range 层原有的 30 秒总预算；用户暂停、重新打开和 seek generation 变化会重置观察窗口。
- 连续缓冲增加的额外储备最多等待 10 秒，然后只要求基础恢复量；不会无条件放开空队列。
  每累计 30 秒有效媒体时间推进，连续卡顿计数降低一级；暂停时间不计入。
- 保留代理的 401/403/404/410 等状态；响应头已经发出后，仍按原始媒体源保存确定的拒绝原因。
  平台打开失败保留底层 Range 原因；增强探测、FFmpeg 打开/读包和路由选择继续保留来源错误。
  鉴权失败停止解码回退，提示访问被拒绝；新的播放尝试使用独立代理，避免继承旧失败。

## 新增日志

| 事件 | 主要信息 | 用途 |
| --- | --- | --- |
| `startup_stage_timing` | stage、elapsedMs、outcome、playbackTrace | 分开源打开、选轨/首包、初始 seek、完整增强准备阶段；区分失败与成功 |
| `enhanced_source_adoption` | preparedDemuxReused、playbackTrace、sourceTrace | 判断是否再次打开同一片源，并关联媒体读取 |
| `enhanced_buffer_progress` | positionMs、phase、requiredMs、bufferedMs、targetMs、waitMs | 说明输出为何等待以及需要多少缓存 |
| 同上 | fillScheduled、readElapsedMs、lastPacketAgeMs、packetsRead、generation | 区分补读未启动、正在阻塞读取、数据正在到达和换代 |
| 同上 | trackBufferedUs、queueBytes、queueSamples、pendingTrackCount、endOfInput、atCapacity | 区分音视频轨饥饿、内存上限及消费阻塞 |
| `transport_range_progress` | rangeId、sourceTrace、rangeStart/end、retry、foreground、phase、status、bytes、elapsedMs | 跟踪请求打开、读取停滞、重试和完成 |

缓冲日志在状态变化时记录，常规采样每 5 秒一次；每轨队列统计仅在日志采样时生成。
单个 Range 等待每 5 秒记录一次，耗时至少 1 秒或失败时记录完成状态，快速成功请求不逐个刷日志。
新记录只包含数值、枚举及进程内加盐散列关联值，不加入媒体 URL、请求头或凭据。
`demuxThroughputBps` 是解复用产出速率，不可直接作为线路带宽。
`trackBufferedUs` 是每轨队列的时间戳跨度，不等同于已经证明连续可播的音视频公共区间。

## 复测

安装本次包后，在同设备、同网络、同原文件、同音轨条件下播放约两分钟。
卡住时保持播放意图，等待约 40 秒，让诊断能够覆盖恢复期限，然后从高级设置的问题诊断中导出 ZIP。
先按 playbackTrace 找起播和缓冲记录，再通过 sourceTrace 查对应 Range，检查卡顿前后字节量、
数据包计数和队列时长是否增长。用其他播放器对照时需确认其直接播放/转码方式与清晰度一致。

用户已有的 23:30 两份包来自 1.0.74 / 1.0.75，不能用于证明这些新日志或修复的真机效果。
本次未接入用户的实际媒体服务器，也未在两台 OPPO 设备上重现。
服务器拒绝 Yfuse 请求的具体原因（凭据、URL 期限或请求兼容性）仍需新日志与服务端记录核对；
客户端正确提示和停止无效回退不会自行授予服务器访问权限。
串行探测耗时及首帧阶段的进一步优化，要依据新记录确认瓶颈后进行，不声称本包已消除全部慢起播。
