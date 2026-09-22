# 播放内核远程熔断

客户端每次自动或手动检查更新时，会独立读取
`https://47.112.219.60/yfuse/playback-policy-v1.json`。策略只能临时关闭已经打包的播放路径，
不能远程开启功能、加载任意库或修改媒体地址；网络失败、过期或格式错误时继续使用 APK
内置行为。

可关闭的路径：

- `ycore.all`：关闭全部 YCore 路径。
- `ycore.demux`：关闭 YCore 解封装，等同于关闭 YCore 路径。
- `ycore.gpu`：只关闭 YCore Vulkan/GPU 增强，保留直接播放。
- `mpv`：普通媒体安全回退 Exo；原盘不降级，直接提示不可用。
- `mdk`：回退 Exo。

发布示例：

```powershell
.\scripts\publish-playback-policy.ps1 -Revision 1 -ValidDays 7 -Disable ycore.gpu,mdk
```

恢复全部路径必须发布更大的 revision，并传入空的 `-Disable`。revision 单调递增且会在策略
过期后继续保留，防止旧策略重放；单次有效期最多 31 天。策略应用情况和分内核 native
崩溃计数会进入一键播放诊断报告。
