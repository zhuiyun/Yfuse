from pathlib import Path
import hashlib, json, shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
assert 'BUILD SUCCESSFUL' in (OUT/'feature-tests.log').read_text(encoding='utf-8')
records = json.loads((OUT/'snapshot.json').read_text(encoding='utf-8'))
for row in records:
    assert hashlib.sha256((ROOT/row['path']).read_bytes()).hexdigest() == row['sha256'], row['path']
totals = {}
for module, variant in [('composeApp', 'testReleaseUnitTest'), ('tvApp', 'testDebugUnitTest')]:
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list((ROOT/module/'build/test-results'/variant).glob('TEST-*.xml'))
    assert files
    for path in files:
        suite = ET.parse(path).getroot()
        for field in counts:
            counts[field] += int(suite.attrib.get(field, 0))
        if any(name in path.name for name in [
            'ThemeCrossfade', 'PlaybackRuntimeContentTest', 'PlayerControlSnapshotTest',
            'PlayerBatteryStatusTest', 'SubtitleBitmapCanvasTest', 'Core2SurfaceTest',
            'NextItemCreditsBoundaryTest', 'PlaybackTimelineKeeperTest',
        ]):
            target = OUT/'test-evidence'/module/path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    assert counts['failures'] == counts['errors'] == counts['skipped'] == 0, counts
    totals[module] = counts
(OUT/'test-summary.json').write_text(json.dumps(totals, indent=2), encoding='utf-8')
phone, tv = totals['composeApp']['tests'], totals['tvApp']['tests']
report = f'''# 主题／播放运行区／ASS 绘制隔离与电量显示

2026-09-10。本批补齐上一轮核对仍有缺口的三项性能问题，并加入播放控制栏电量。变更清单包含 {len(records)} 个文件，其中 9 个新增；多数页面仅调整文字、图标的消费组件导入。工作区其他改动未回退。

- 主题只发布一次目标 Palette／强调色，移除全局逐帧构建 Palette 的旧动画。文字、图标在自身组件内过渡，背景和玻璃在绘制阶段读颜色。普通状态／海报颜色更新不启动额外主题协程；快速反向切换、过渡中改强调色、减弱动效及隐藏路由有回归覆盖。
- 播放运行区订阅结构状态，实时进度和诊断单独提供给时间轴、字幕、同步、进度上报和诊断面板。顶部码率／帧率、投屏时间与音画偏移仍读取完整实时值。书签、弹幕在操作发生时捕获进度。跨内核切换首个状态保留同一媒体时间轴，但不会继承旧内核的结束／错误标志。片尾成员判断和正向进度观察改为独立实时订阅，原倒计时暂停／取消规则保留。
- ASS 位图转换移到 Default 调度器，完整画面原子发布；Canvas 读取像素状态，不由位图流驱动字幕组件重组。双字幕只在展示边界变化时重新测量，保留缩放、亮度、背景、轨道堆叠及原定位规则。静态 PGS 位图可复用已有图片对象。
- 顶部时间旁显示电池图标、百分比与充电标记，随播放控制按钮显示／隐藏。通过系统电量广播更新，离开控制栏后注销监听；无电池或无效数据不显示虚假的 0%。

此前已修复的详情顶栏、库首页滚动派生态、光球／装饰时钟的减弱动效、主色 State 返回值和海报 BlurEffect 缓存保持不变。

## 验证

- 本地 `:composeApp:testReleaseUnitTest`：{phone} 项，失败／错误／跳过均为 0。
- 本地 `:tvApp:testDebugUnitTest`：{tv} 项，失败／错误／跳过均为 0。合计 **{phone + tv} 项通过**。
- 手机 Release 与 TV Debug Kotlin 编译通过；设计系统检查、手机／TV 模块边界及本批 ktlint、`git diff --check` 通过。
- 回归覆盖真实 Compose 订阅隔离（进度与诊断更新）、主题颜色时钟中断、内核切换首个状态、字幕像素替换不改几何、位图缩放／双轨定位、电量异常值及溢出。
- `snapshot.json` 保存本批最终文件 SHA-256，`diff/` 为相对本批开始时的差异，`test-evidence/` 为相关测试 XML。

未连接手机，未进行真机视觉、实际功耗或 8／24 小时设备矩阵测试；本批未签名打包或上传代码。编译保留项目已有的弃用等警告。
'''
(OUT/'RESULTS.md').write_text(report, encoding='utf-8')
print(json.dumps(totals))
