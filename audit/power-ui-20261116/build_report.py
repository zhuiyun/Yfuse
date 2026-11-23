"""Build an offline report from recorded audit evidence, with no inferred energy savings."""
import base64
import csv
import datetime
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113' / 'power-ui-20261116'


def load(name):
    return json.loads((ROOT / name).read_text(encoding='utf-8'))


def esc(value):
    return html.escape(str(value if value is not None else '不可测 / 未提供'))


def status_label(issue):
    if issue.get('statusZhFor') == issue.get('status'):
        return issue.get('statusZh', issue.get('status'))
    return issue.get('status')


def embedded_image(path, description):
    if not path.exists():
        return ''
    payload = base64.b64encode(path.read_bytes()).decode()
    return f'<figure><img alt="{esc(description)}" src="data:image/png;base64,{payload}"><figcaption>{esc(description)}</figcaption></figure>'


def table(headers, rows):
    head = ''.join(f'<th scope="col">{esc(h)}</th>' for h in headers)
    body = ''.join('<tr>' + ''.join(f'<td>{esc(v)}</td>' for v in row) + '</tr>' for row in rows)
    return f'<div class="table"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


def playback_and_poster_recheck():
    package = load('package-verification-30.json')
    formats = load('format-and-completion-30.json')
    holds = load('cached-preview-30.json')
    controls = load('control-verification-30.json')
    issue = load('iteration-31-verification.json')
    detail = load('iteration-32-verification.json')
    batch = load('final-public-partial-30.json')
    assert batch['complete'] and batch['apkSha256'] == package['apkSha256']
    assert package['apkSha256'] == formats['apkSha256'] == holds['apkSha256'] == controls['installedApkSha256']
    rows = []
    for core, fmt in formats['cores'].items():
        preview = holds['results'][core]
        rows.append((core, fmt['opened'], fmt['confirmedSeeks'], fmt['completion']['naturalRewindsWithPicture'],
                     len(preview['phases']), preview['requestsWhileHeld']))
    return ('<h2>第 30 轮已核对的云端结果</h2>'
            + table(['内核','10 种样本起播','40 次拖动','4 次自然结束后回拖','7 个缓冲缩略图阶段','按住时新增请求'], rows)
            + '<p>同一 APK 上，YCore 和 SenPlayer 各确认了 13,760 次拖动。另有 88 个公共原文件，两内核各完成 88 次起播和 176 次拖动。'
              '<a href="final-public-partial-30.json">已完成的批量结果</a> · <a href="public-original-files-30.csv">逐个原文件结果</a>。</p>'
              '<p>同一安装包通过了 96 项解码、软件输出及失败恢复检查。已核对 YCore 库、五项 SenPlayer 原始资产、'
              'DEX 与 16 KiB 对齐。云测安装包使用测试签名。'
              '<a href="format-and-completion-30.json">视频格式与片尾检查</a> · '
              '<a href="cached-preview-30.json">缓冲缩略图</a> · <a href="control-verification-30.json">播放与恢复</a> · '
              '<a href="package-verification-30.json">APK 核验</a> · <a href="legacy-code-scan-30.json">旧内核检查与清理记录</a>。</p>'
              '<h3>发现页与媒体库的轮播页码</h3><p>实际截图覆盖两种显示配置，各含八张发现页海报和一个媒体库状态。'
              '旧版有 ' + esc(issue['before']['belowThree']) + ' 张截图中的选中指示条对比度低于 3:1。'
              '修复版在同一组场景的 18 张实际截图中全部达到 3:1，浅色最低 6.35:1，深色大字号最低 6.81:1。'
              '<a href="discovery-review-30/indicator-contrast.json">修复前 18 次测量</a> · <a href="discovery-review-31/indicator-contrast.json">修复后 18 次测量</a> · '
              '<a href="discovery-review-30/light-discovery.jpg">浅色海报</a> · '
              '<a href="discovery-review-30/dark-large-discovery.jpg">深色大字号海报</a> · '
              '<a href="iteration-31-verification.json">修复与复测记录</a>。</p><h3>电影和剧集详情的文件信息</h3>'
              '<p>修复前，三类详情页的深色大字号配置共有 30 处文字对比度低于 4.5:1。第 32 轮复测浅色和深色各 30 处全部通过，'
              '最低对比分别为 ' + esc(detail['after']['light']['minimum']) + ':1 和 ' + esc(detail['after']['dark-large']['minimum']) +
              ':1。全页审查覆盖 72 条路径、144 个滚动终点和 302 个视口。备份页的受保护内容无法截图。'
              '<a href="ui-review-32/overview.html">全页截图索引</a> · <a href="ui-review-32/file-info-cards.jpg">六张文件信息卡片</a> · '
              '<a href="ui-review-30/file-info-contrast.json">修复前测量</a> · <a href="ui-review-32/file-info-contrast.json">修复后测量</a>。</p>')


def profile5_source_progress():
    origin = load('private-origin-32.json')
    private = load('dolby-profile5/private-frame-metadata.json')['files']
    last = load('private-verification-28.json')
    candidate = load('iteration-33-verification.json')
    positions = sum(len(w['ranges']['verifiedPositions']) for w in origin['works'])
    assert len(origin['works']) == 60 and positions == 180 and origin['loggedOut']
    frames = sum(len(f['frameMetadata']) for f in private.values())
    counts = last['totals']
    return ('<h2>私人片源与第 33 轮解码修复</h2><p>32 部电影和 28 集剧集按类型、字节数、时长与容器唯一匹配。'
            '两集的目录顺序发生了变化，转发器已按文件身份重新定位。60 个原文件在服务器端的 180 次范围请求均取得正确字节。'
            '上次用于修复版复验的两台云端模拟器在放行前到期，该次没有执行私人播放。'
            '<a href="private-origin-32.json">服务端分段证据</a> · <a href="private-attempt-30.json">到期运行记录</a>。</p>' +
            table(['内核','最近一次私人实播确认起播','确认拖动','已发出的拖动','原定拖动'],
                  [(k, str(v['movingWithSelectedCore']) + '/60', v['confirmedSeeks'], v['issuedSeeks'], v['plannedSeeks']) for k,v in counts.items()]) +
            '<p>六个杜比视界 Profile 5 原文件各抽取 12 帧，共 ' + str(frames) + ' 帧含逐帧 RPU 多项式。'
            '独立公共色卡的 48 个位置与参考渲染对比，单通道最大差为 PQ 88/65535、SDR 1/255。'
            '在 ARM64 Android 上，已逐帧复核拖动前后颜色，并验证缺少 RPU 时不会输出错误画面。'
            '同一候选通过 ' + str(candidate['cloud']['host']['tests']) + ' 项主机测试、320 项重复检查和 12 项设备用例。'
            '单独生成的 64 秒公开播放样本含 355 个带 RPU 的画面。私人原文件的起播和拖动仍待修复版复验。'
            '<a href="iteration-33-verification.json">第 33 轮设备验证</a> · '
            '<a href="dolby-profile5/private-frame-metadata.json">匿名原片帧元数据</a> · '
            '<a href="../legacy-engine-20261113/power-ui-20261116/dolby-native-33/profile5-colour-reference/reference.json">独立参考值</a>。</p>')


def current_playback_evidence():
    if not (ROOT / 'package-verification-36.json').is_file():
        return ''
    package = load('package-verification-36.json')
    rows = [('第 36 轮云测 APK', f"版本 {package['versionName']}（{package['versionCode']}）。YCore 原生库、SenPlayer 五项原始资产、16 KiB 对齐和 {package['dex']['methods']:,} 个含代码方法均通过检查。")]
    links = [('package-verification-36.json', '实测安装包与证书')]
    held = ROOT / 'cached-preview-36.json'
    if held.is_file():
        tabs = load(held.name)
        assert tabs['apkSha256'] == package['apkSha256']
        for core, data in tabs['results'].items():
            rows.append((core + ' 随拖预览', f"缓存内显示第 {data['cachedThumbnails'][-1]:g} 秒的画面，进入未缓存位置后缩略图消失。拖回缓存又显示第 {data['returnThumbnails'][-1]:g} 秒画面。整个按住阶段新增请求 {data['requestsWhileHeld']} 次。"))
        links += [(held.name, '缓存、传输与画面记录'), ('cached-preview-36.png', '两内核在三个位置的截图')]
    public = ROOT / 'final-public-partial-36.json'
    if public.is_file():
        totals = load(public.name)
        for core, data in totals['cores'].items():
            rows.append((core + ' 原片与时码片段', f"已核对原片 {data['originalFilesCompleted']}/88，确认原片拖动 {data['originalSeeksConfirmed']} 次。独立时码片段已核对 {data['confirmedSeeks']:,}/13,760 次。"))
        links.append((public.name, '逐分片的首次实播结果'))
        if (ROOT / 'public-original-files-36.csv').is_file():
            links.append(('public-original-files-36.csv', '15 部电影和 73 集剧集的格式、大小及两内核落点'))
    volume = ROOT / 'original-volume-36.json'
    if volume.is_file():
        measures = load(volume.name)
        for core, values in measures['metrics']['volume'].items():
            rows.append((core + ' 原文件连续拖动', f"88 个原文件计划 {values['expectedGestures']:,} 次，已发出 {values['issued']:,} 次，已确认 {values['verified']:,} 次。门槛预检单独统计。"))
        links.append((volume.name, '完整原文件连续操作记录'))
    scaled = ROOT / 'sen-scaled-child-36.json'
    if scaled.is_file():
        values = load(scaled.name)['transports']
        percentages = [v['windows'][0]['percentOfOneCore'] for v in (values['file'], values['http'])]
        rows.append(('SenPlayer 全屏渲染', f"同一 1280×720 窗口的应用 CPU 占用相当于单核的 {percentages[0]:.2f}%（文件）及 {percentages[1]:.2f}%（HTTP）。把视频子视图缩小再放大后，两条路径的活动画面都只覆盖左下四分之一，该改动已排除。"))
        links.append((scaled.name, '缓冲与画幅测量'))
    cadence = ROOT / 'render-cadence-36.json'
    if cadence.is_file():
        detail = load(cadence.name)
        for core, values in detail['medians'].items():
            rows.append((core + ' 同机全屏对照', f"播放图层 {values['averageFPS']:.2f} 帧/秒，应用 CPU 相当于单核的 {values['appCpu']:.2f}%，系统合成进程为 {values['surfaceFlingerCpu']:.2f}%。两次采用相同的 24 帧/秒片源。"))
        math_share = detail['medians']['SenPlayer']['powfPercentOfSampledAppCpu']
        rows.append(('SenPlayer 函数采样',f"两次前台播放中，系统幂函数 __ieee754_powf 的应用 CPU 采样占比中位数为 {math_share:.2f}%。当前数据无法确定由哪项色彩变换调用。"))
        links += [(cadence.name, '运行环境、函数与画面记录'), ('render-cadence-36.csv', '四个播放窗口实测值'), ('render-cadence-36.png','全屏 CPU 与实际帧率图')]
    contrast = ROOT / 'dialog-contrast-36.json'
    if contrast.is_file():
        value = load(contrast.name)['samples']
        rows.append(('播放状态标签', f"网络阶段 {value['连接与读取']['contrast']:.2f}:1，播放器 {value['播放器']['contrast']:.2f}:1，视频输出 {value['视频输出']['contrast']:.2f}:1。正文字色已用于修复标签，第 37 轮对黑、白和原始窗口背景进行同机对照。"))
        links += [(contrast.name, '修复前的逐像素测量'), ('iteration-37-verification.json', '第 37 轮验证记录')]
    round38 = ROOT / 'iteration-38-verification.json'
    if round38.is_file():
        run = load(round38.name)
        rows.append(('投屏设备列表', run['status']))
        links += [(round38.name, '投屏修复与本轮状态'), ('cast-discovery-failure-36.json', '两台云端设备的原始故障')]
        first = load('original-first-touch-36.json')['observations']
        rows.append(('原片首拖', f"四个文件均已起播。YCore 21/24 次、SenPlayer 23/24 次到达目标且画面推进。{len(first)} 次首拖偏离目标，完整原文件压力测试另行计数。"))
        links += [('original-first-touch-36.json', '四次首拖的落点和时间记录')]
        large = load('large-public-original-36.json')
        rows.append(('大型 4K 原片', f"原文件 {large['file']['contentBytes']:,} 字节。两个内核均成功起播，分别完成四次前后拖动，并读取 4 GiB 之后的原始字节。"))
        links += [('large-public-original-36.json', '原片指纹、实际读取偏移和进度')]
        links += [('ui-review-36/overview.html', '最近的全页视觉复核'), ('ui-review-36/secure-backup-controls.json','备份控件与触控边界')]
        power = load('four-hour-verification-33.json')
        for result in power['results']:
            phase = next(v for v in result['phases'] if v['phase']=='playing')
            rows.append((result['core'] + ' 四小时活动采样', f"{result['durationSeconds']/60:.2f} 分钟，{result['samples']} 次采样；前台播放应用 CPU 中位数为单核的 {phase['cpuMedianPercentOneGuestCore']:.2f}%。虚拟电量维持在 {result['simulatedBatteryLevels'][0]}%。"))
        links += [('four-hour-verification-33.json','十三个阶段的配对观察'), ('activity-by-phase-33.csv','每阶段活动数据')]
        caller = load('render-cadence-callers-36.json')
        rows.append(('软件图形后端', f"第二次交替运行使用 ANGLE 和 SwiftShader。两内核均为 {caller['medians']['YCore']['averageFPS']:.2f} 帧/秒，SenPlayer 的系统幂函数占应用 CPU 采样的 {caller['medians']['SenPlayer']['powfPercentOfSampledAppCpu']:.2f}%。帧指针采样未还原直接调用方。"))
        links += [('render-cadence-callers-36.json','图形驱动、采样结果与调用栈限制')]
    if (ROOT / 'iteration-39-verification.json').is_file():
        current = load('iteration-39-verification.json')
        checks = current['previousComparison']
        rows.append(('播放状态弹窗复测', f"相同 40 个测点中，第一次调整后仍有 {checks['remainingAfterTextOnlyChange']} 个不足 4.5∶1，最低 {checks['minimum']:.2f}∶1。第 39 轮对面板使用不透明底色。{current['qualityStatus']}"))
        links += [('problem-contrast-comparison-37.json','两版逐个测点的截图复算'), ('iteration-39-verification.json','最新一轮验证记录'), ('legacy-code-scan-39.json','代码、界面和依赖清理核对')]
        rows.append(('前台 CPU 热点线程', '独立重复时，SenPlayer 的四个工作线程各有约 15% 的应用 CPU 采样落在幂函数内，四者均有模拟器 Vulkan 图形驱动采样。具体调用方仍未还原。'))
        links += [('render-cadence-unwind-36.json','双内核渲染线程与函数采样'), ('format-and-completion-36.json','14 组格式及播完回拖结果')]
    if (ROOT / 'iteration-40-verification.json').is_file():
        current = load('iteration-40-verification.json')
        scan = load('legacy-code-scan-40.json')
        rows.append(('旧播放器字幕源码', f"扩大检查后，在 {scan['checkedBefore']:,} 个文件中找到旧版未使用的字幕显示组件。删除后重新检查 {scan['checkedAfter']:,} 个文件。旧设置仍可迁移到 YCore。{current['status']}"))
        links += [('previous-subtitle-symbols-36.json','旧版 R8 字幕符号对照')]
        links += [('legacy-code-scan-40.json','旧组件的位置、调用核对与剩余命中'), ('subtitle-cleanup-host-40.json','字幕和设置迁移等 198 项本地测试'), ('iteration-40-verification.json','第 40 轮状态')]
    if (ROOT / 'iteration-41-verification.json').is_file():
        rows.append(('播放状态文字与建议', '第 41 轮测量浅色、深色主题及五组背景。45 组文字的首次可交互画面和稳定画面均达到 4.5∶1，最低实测分别为 8.50∶1 和 14.64∶1。'))
        links += [('problem-contrast-comparison-41.json','45 组首帧与稳定帧对照'), ('package-verification-41.json','优化构建、签名和内核清单')]
    if (ROOT / 'same-apk-first-touch-41.json').is_file():
        for core, v in load('same-apk-first-touch-41.json')['totals'].items():
            rows.append((core + ' 同一 APK 首拖对照', f"四个原文件逐个使用相同目标重测。{v['verified']}/{v['issued']} 次到达目标并持续出画，独立源画面核对覆盖 {v['filesPassed']}/{v['filesMeasured']} 个文件。"))
        links += [('same-apk-first-touch-41.json','逐文件、逐目标同版对照'), ('original-volume-41.json','88 个原文件的上万次拖动，含当前已回收数量')]
    if (ROOT / 'cast-device-contract-42.json').is_file():
        cast_file = 'cast-device-contract-43.json' if (ROOT/'cast-device-contract-43.json').is_file() else 'cast-device-contract-42.json'
        for item in load(cast_file)['results']:
            checks = item['checks']
            mode = '正常时钟' if item['audioClock'] == 'paced' else '异常时钟'
            resume = '恢复位置正确' if checks['resumedAtPausedPicture'] else '继续后从第 0 秒重新出画'
            local = '成功接回本机' if item['localPlaybackReturned'] else '未验证接回本机继续出画'
            rows.append((item['core'] + ' 投屏 · ' + mode, f"前拖和后拖均到达目标。接收器在第 {item['receiverPaused']} 秒暂停，手机显示第 {item['phonePaused']} 秒。{resume}，{local}。"))
        links += [(cast_file,'当前四台接收器的源时间码与恢复首帧'), ('cast-device-contract-42.json','前一轮源时间码与定位命令'), ('iteration-42-verification.json','第 42 轮资格与验证记录')]
    if (ROOT / 'sen-initial-output-40.json').is_file():
        for medium, result in load('sen-initial-output-40.json')['transports'].items():
            cpu = result['medianPercentOneCore']
            rows.append(('SenPlayer 渲染对照 · ' + medium, f"同设备四个 30 秒交叉窗口。1280×720 缓冲的 CPU 中位数为 {cpu['1280']:.2f}%，640×360 缓冲为 {cpu['640']:.2f}%（一个来宾核心为 100%）。全屏与字幕层均为 1280×720。"))
        if (ROOT/'sen-library-buffer-43.json').exists():
            for medium, result in load('sen-library-buffer-43.json')['transports'].items():
                cpu = result['medianPercentOneCore']
                label = '本地文件' if medium == 'file' else 'HTTP'
                rows.append(('第 43 轮正常播放器 · '+label, f"完整视频缓冲的单核 CPU 中位数为 {cpu['1280']:.2f}%，按片源尺寸创建时为 {cpu['640']:.2f}%。四个窗口的时间码都推进 30 秒。"))
            links += [('sen-library-buffer-43.json','正常播放器八个窗口的测量与画面校验'),('sen-library-buffer-cpu-43.png','正常播放器的逐窗口 CPU 曲线')]
        else:
            rows.append(('第 43 轮渲染', '按实际片源信息在创建视频输出前选择尺寸，正常媒体库入口长测正在复验。'))
        if (ROOT/'subtitle-regressions-43.json').is_file():
            subtitles = load('subtitle-regressions-43.json')
            for case in subtitles['cases'].values():
                subtitle_state = '已通过' if case['passed'] else '未通过'
                detail = "选轨、再次开启及两路大号字幕均从实际画面读回。" if case["passed"] else "重新选轨后字幕可见。切后台再返回时，连续八张截图缺少长字幕。"
                rows.append((case['core']+f" · 每条字幕 {case['duration']} 秒", f"{case['checks']} 项观察，{subtitle_state}。{detail}"))
            links += [('subtitle-regressions-43.json','按时长、内核列出的字幕画面与校验值')]
            if (ROOT/'subtitle-foreground-failure-43.json').exists(): links.append(('subtitle-foreground-failure-43.json','YCore 返回前台时缺字幕的原始画面及时间线'))
        links += [('sen-initial-output-40.json','八个窗口的原始测量'), ('subtitle-cloud-diagnosis-41.json','两种字幕时长与主线程异常'), ('four-hour-verification-34.json','第 34 轮双核四小时数据'), ('activity-by-phase-34.csv','13 个场景的采样数据')]
        if (ROOT / 'sen-initial-buffer-cpu.png').is_file():
            links.append(('sen-initial-buffer-cpu.png','同机逐窗口 CPU 曲线'))
    if (ROOT / 'package-verification-43.json').is_file():
        latest = load('package-verification-43.json')
        qualification = load('iteration-43-verification.json')
        rows.append(('第 43 轮云测版本', f"资格检查通过，{qualification['hostUniquePassed']:,} 项主机用例、{qualification['repeatedCases']} 次重复与 {qualification['positiveDeviceTests']} 项 ARM 设备检查通过。APK 含 {latest['dex']['methods']:,} 个有代码的方法，DEX 检查发现 {latest['dex']['findings']} 项异常。"))
        rows.append(('第 43 轮安装包与 UI', '已核对两项 YCore 主库和五项 SenPlayer 原始资产。播放诊断屏的 45 组文字共 90 次像素复核通过。'))
        links += [('package-verification-43.json','第 43 轮实际 APK'), ('release-input-verification-43.json','YCore 来源与五项 SenPlayer 文件的校验值'), ('problem-contrast-comparison-43.json','第 43 轮文字像素复核'), ('iteration-43-verification.json','本轮各云测运行与状态')]
    if (ROOT/'large-public-original-43.json').is_file():
        large = load('large-public-original-43.json')
        for name, observed in large['cores'].items():
            rows.append((name+' · 6.74 GB 的 4K MOV', f"起播确认耗时 {observed['openSeconds']:.1f} 秒。两种手势各去往 82% 与 21%，四次均持续出画。HTTP 日志确认读取超过 4 GiB 的文件偏移。"))
        links.append(('large-public-original-43.json','原文件大小、四个拖动目标及超 4 GiB 读取证据'))
    if (ROOT/'iteration-44-verification.json').is_file():
        links += [('iteration-44-verification.json','第 44 轮字幕恢复修复与实测进度'),('host-regressions-44.json','226 项主机回归与 XML 校验值')]
    if (ROOT/'emby-login-preflight-43.json').is_file():
        auth = load('emby-login-preflight-43.json')
        if all(auth[k] for k in ('authenticated','movieCatalogReadable','episodeCatalogReadable','sessionLoggedOut')):
            rows.append(('Emby 登录与目录访问', '已从服务器本机登录，分别读取电影与剧集，随后已成功注销验证会话。'))
            links.append(('emby-login-preflight-43.json','登录、目录读取与注销结果'))
    return ('<h2>当前播放、缓存预览与渲染验证</h2>' + table(['项目', '实测结果'], rows) + '<p>' +
            ' · '.join(f'<a href="{path}">{label}</a>' for path, label in links) + '。</p>')


def candidate_progress_45():
    if not (ROOT / 'iteration-45-verification.json').is_file():
        return ''
    it = load('iteration-45-verification.json')
    original = load('private-source-control-43.json')
    ui = load('ui-visual-verification-43.json')
    before = load('profile-detail-contrast-before-45.json')
    cast = load('receiver-resume-protocol-43.json')
    formats = load('format-verification-43.json')
    host = load('host-regressions-45.json')
    thumbnails = load('cached-thumbnail-verification-43.json')
    completion = load('natural-completion-verification-43.json')
    rows = [
        ('私人原片', f"在 Emby 服务器内已核对 {original['filesByKind']['Movie']} 部电影和 {original['filesByKind']['Episode']} 集剧集，共 {original['rangeChecks']} 次区间读取。59 个原片保留，1 个旧文件没有同大小条目，已补选一集 3840×1608 HEVC。源站数据与云端播放分别验收。"),
        ('两内核格式专项', f"第 43 轮的 {formats['fixtureCount']} 个受控片源包含 HEVC Main 10、AV1、VP9、ProRes 和交错扫描等格式。两个内核都起播 14/14，每个内核各 56/56 次拖动确认连续画面。"),
        ('拖动缩略图', f"逐张解码两内核共 {sum(len(v['screens']) for v in thumbnails['cores'].values())} 张源画面截图。两者均在已缓冲的第 10 秒显示画面、第 143 秒缓冲缺口处隐藏、拖回后显示第 11 秒。按住期间源站已发送字节数和成功 Range 计数均无增长，图片接口请求为零。此次测试未统计被拒的 GET/HEAD。"),
        ('片尾与重播', f"两内核共 {sum(len(v['checks']) for v in completion['cores'].values())} 例自然结束后拖回 21% 处，均有继续推进的时间码和连续画面。"),
        ('功耗长测', '两内核均完成 13 阶段预检，各 27 次采样。已确认拖动阶段继续使用同一个媒体库播放进程。正式四小时结果以完整采样文件为准。'),
        ('投屏暂停恢复', f"两种接收器时钟、四种命令顺序，独立核对 {cast['completedControls']*24} 张连续帧。时钟异常时，先播放再定位，两次对照都从第 0 秒开始。暂停状态先定位再播放，两次首帧均为第 21 秒，并连续推进到 26 秒。"),
        ('界面', f"已复查 72 页的浅色与深色大字号，共 {ui['routeVariants']} 个页面配置。{ui['normalCaptureCount']} 次取得应用可见画面，{len(ui['protectedCaptures'])} 次为 Android 防截屏窗口。已修复重复推荐文案，并修复由两种主题共 {sum(x['belowFourPointFive'] for x in before['results'])} 处读数发现的四组低对比文字。"),
        ('合并修复版', f"本地格式、Android 测试编译和 {host['uniqueChecks']} 项主机检查通过。云端资格检查运行 {it['qualificationRun']}，发布验收继续进行。"),
    ]
    links = [('private-source-control-43.json','私人片源逐段校验与替换记录'),
             ('format-verification-43.json','14 种片源和逐次拖动'), ('library-power-pilot-43.json','两内核功耗预检'),
             ('receiver-resume-protocol-43.json','投屏连续帧和时钟对照'), ('ui-review-43/overview.html','当前页面和滚动截图'),
             ('ui-visual-verification-43.json','截图范围与受保护窗口'), ('profile-detail-contrast-before-45.json','修复前逐标签读数'),
             ('cached-thumbnail-verification-43.json','拖动期间的截图时间码与源站计数'),
             ('natural-completion-verification-43.json','片尾与恢复的逐片画面记录'),
             ('iteration-45-verification.json','第 45 轮验收状态'), ('host-regressions-45.json','本地主机回归记录')]
    if (ROOT / 'iteration-46-verification.json').is_file():
        newest = load('iteration-46-verification.json')
        failed = load('public-movie-seek-timeouts-43.json')
        paired = load('public-movie-control-43.json')
        original_stress = load('original-volume-41.json')['metrics']['volume']
        coffee = load('public-coffee-frame-analysis-41.json')
        selected = next(row for row in coffee['observations'] if (row['core'],row['gesture'],row['imageIndex']) == ('YCore',2,0))
        rows[0] = ('私人原片', '服务器核对了 32 部电影、28 集剧集的 180 个区间。59 个文件与上轮相同，另补选一集 3840×1608 HEVC。两台云测机已领取密文片单并进入原片与双内核播放步骤。')
        rows.extend([
            ('公开原文件压力测试', f"第 41 轮已收到的逐次记录中，YCore 有 {original_stress['YCore']['verified']:,} 次确认，SenPlayer 有 {original_stress['SenPlayer']['verified']:,} 次确认。原文件对照图像还能独立印证其中 {sum(v['sourceCorroboratedGestures'] for v in original_stress.values()):,} 次。"),
            ('画面争议', f"《Coffee Run》原文件有 {coffee['timeline']['frames']:,} 帧。对同一张 Android 截图按每秒六帧抽样，附近匹配分数为 {selected['cloudSixHz']['nearScore']:.4f}。按原生时间戳全量比对，附近第 {selected['independent']['nativePTS']['nearSecond']:.3f} 秒的分数为 {selected['independent']['nativePTS']['nearScore']:.4f}。其余失败继续按原随机序列复测。"),
            ('公开原片网络卡顿', f"第 43 轮 SenPlayer 在《Big Buck Bunny》拖到 63% 和 21% 后，分别到第 {failed['touches'][0]['stationaryThroughSeconds']:.0f} 秒、第 {failed['touches'][1]['stationaryThroughSeconds']:.1f} 秒采样时仍停在同一位置。另两台用相同 APK 分别直连源站和读取字节相同的本地副本，共 {sum(a['confirmedSeeks'] for a in paired['arms'].values())} 次拖动通过。首次两次超时保留。"),
            ('字幕恢复', '两内核的短字幕和 598 秒长字幕均完成选轨、关闭、重开、真实拖动和前后台切换。四组共 148 项观察，20 张保存的画面可读回预期字幕。'),
            ('第 46 轮', f"云端有 {newest['hostUniquePassed']:,} 项不同主机测试、{newest['repeatedCases']} 次重复、{newest['positiveDeviceTests']} 项 ARM 测试通过。{newest['negativeControls']} 项旧实现对照在期望断言处失败。续读测试验证从第一个缺失字节恢复，随后断开源站也能从缓存读回相同数据。")])
        links.extend([('iteration-46-verification.json','第 46 轮运行清单'),('private-cloud-handoff-43.json','两台云测机领取记录'),
                      ('public-movie-seek-timeouts-43.json','停顿时间与上游读取记录'),('public-movie-control-43.json','同一电影两种来源对照'),
                      ('original-volume-41.json','原文件压力测试逐项记录'),('public-coffee-frame-analysis-41.json','真实截图逐帧重算'),
                      ('host-regressions-46.json','续读回归记录'),('subtitle-regressions-44.json','四组字幕与返回前台的逐张截图校验'),
                      ('film-body-timeout-controls-46.json','同一影片延迟读取的对照规则'),('problem-contrast-comparison-46.json','当前候选与旧实现的逐项对比度')])
    if (ROOT / 'public-movie-recovery-headers-46.json').is_file():
        recovery = load('public-movie-recovery-headers-46.json')
        rows.append(('公开电影续读超时', f"同一公开电影清空媒体缓存后，SenPlayer 在 {recovery['firstMovingPicture']['secToConfirm']:.2f} 秒确认起播。第一次拖到 63% 后画面停在第 399 秒，第 42 秒采样才看到进度推进，超过验收时间。原版调用栈显示，缺失区间的续读请求在等待 HTTP/2 响应头时超时。第二次拖到 21% 在 {recovery['secondSeek']['secToConfirm']:.2f} 秒内通过。"))
        links.extend([('public-movie-recovery-headers-46.json','逐次画面采样与原始调用栈位置'),
                      ('package-verification-46.json','候选 APK 版本与签名证据'),('candidate-binary-inspection-46.json','实际 DEX、两套内核与原生库对齐'),
                      ('diagnostics-targets-43.json','受保护窗口的两种主题与触控尺寸'),('module-network-meter-46.json','模块网络计数的独立校验')])
    if (ROOT / 'private-originals-43-by-file.json').is_file():
        private = load('private-originals-43-by-file.json')
        y, s = (private['totals'][core] for core in ('YCore','SenPlayer'))
        rows[0] = ('私人原片', f"源站核对了 32 部电影和 28 集剧集的 180 个区间。已取回 {len(private['shards'])} 台云测机的记录。YCore 对 {y['observedOpens']} 部执行起播并确认 {y['movingWithSelectedCore']} 部画面推进，实际发出的 {y['issuedSeeks']} 次拖动通过 {y['confirmedSeeks']} 次。SenPlayer 对 {s['observedOpens']} 部执行起播并确认 {s['movingWithSelectedCore']} 部画面推进，实际发出的 {s['issuedSeeks']} 次拖动通过 {s['confirmedSeeks']} 次。每个文件的缺测和失败均保留。")
        links.extend([('private-original-files-43.csv','私人片源逐片逐内核记录'),('private-originals-43-by-file.json','私人片源首次结果与复测结果')])
    if (ROOT / 'private-verification-43.json').is_file():
        private = load('private-verification-43.json')
        latency = private['cloudPreResponseFailureLatencyMs']
        rows.append(('私人转发链路', f"源服务器的 {private['actualSourceTransactions']:,} 次请求全部返回 HTTP 206。云端有 336 次在收到远端响应前失败，中位耗时 {latency['median']:.0f} 毫秒。另一台云主机没有出现这类错误。九个文件在分段校验后没有进入播放器。"))
        links.extend([('private-verification-43.json','源站与两台模拟器的请求核对'),('private-failure-scenarios-43.csv','逐片失败场景')])
    if (ROOT / 'host-regressions-48.json').is_file():
        ranges = load('private-ycore-startup-ranges-43.json')
        rows.append(('YCore 大文件起播', f"一部 {ranges['original']['size'] / 1e9:.2f} GB 的 HEVC 电影在五个块首重复读取 128 KiB 和 2 MiB，软件路径在 {ranges['enhancedDeadline']['sinceDirectFailureMs'] / 1000:.3f} 秒后终止。已修改连续读取时下一块的请求方式，60 项主机回归通过。"))
        rows.append(('SenPlayer 公开电影续读', '第 47 轮在主机复现了旧版等待 HTTP/2 响应头时超时的相同调用栈。修复后，缺失字节从独立的加密连接取得。56 项主机检查通过。'))
        nearby = load('private-ycore-startup-comparison-43.json')['casesWithClosedSourceOpenWindow']
        count = sum(any(a['prefixThenFullOffsetsBeyondFirst'] for a in record['attempts']) for record in nearby.values())
        rows.append(('同类起播失败', f"在能按设备时间划定起止的原片中，共有 {count} 部电影或剧集在首块以外出现相同的重复请求。独立 HTTP 回归在旧代码的 2 MiB 偏移复现了两次请求，修复后同一位置只有一次，读回内容的 SHA-256 一致。"))
        links.extend([('private-ycore-startup-ranges-43.json','真实电影的起播区间'),('private-ycore-startup-comparison-43.json','电影和剧集的逐次请求时间窗'),
                      ('host-regressions-48.json','连续读取实际字节与区间校验'),
                      ('host-regressions-47.json','续读新旧实现的主机对照'),('public-film-fault-device-46.json','同一电影的四组云端实测'),
                      ('iteration-48-verification.json','第 48 轮运行与待验项目')])
    if (ROOT / 'diagnostics-targets-45.json').is_file():
        current_ui = load('profile-detail-contrast-after-45.json')
        light, dark = current_ui['checks']
        destinations = load('ui-destinations-45.json')
        rows.append(('两种主题的页面复核', f"{current_ui['routeVariants']} 个页面配置有 {current_ui['imageObservations']} 次截图观察，其中 {destinations['verifiedPageDestinations']} 个配置验证了正确目的地。深色大字号空媒体库误截为服务器页，已扣除。20 个复测文字区域的对比度全部达到 4.5∶1，浅色最低 {light['lowestAfter']:.2f}∶1，深色最低 {dark['lowestAfter']:.2f}∶1。五次受保护的诊断页截图按系统控件树核对，两种主题分别确认 12 个可操作控件。"))
        links.extend([('ui-review-45/overview.html','第 45 轮逐页截图'),('ui-review-45/changed-labels.png','浅色与深色文字前后对照'),
                      ('profile-detail-contrast-after-45.json','逐处像素对比度'),('diagnostics-targets-45.json','受保护页实际控件与尺寸'),
                      ('ui-destinations-45.json','逐页目的地和误截证据')])
    if (ROOT / 'module-power-pilot-46.json').is_file():
        pilot = load('module-power-pilot-46.json')
        rows.append(('八个模块短测', f"共记录 {int(pilot['recordedPhaseSeconds'])} 秒，取得 {pilot['samples']} 个采样点。进入 IPTV 和 WebDAV 共请求 4 次，相应页面的空闲观察期间没有新增对测试源的请求。临时 Wi-Fi 接收端退到后台后已验证停止监听。"))
        links.extend([('module-power-pilot-46.json','逐模块 CPU、网络及模拟电量'),('../' + pilot['curveFile'],'八个模块的原始曲线 CSV')])
        if pilot.get('activityCurve'):
            links.extend([(pilot['activityCurve'],'八个模块的活动曲线图'),(pilot['serviceCurvesCsv'],'八个模块的系统计数 CSV')])
    if (ROOT / 'large-public-original-46.json').is_file():
        large = load('large-public-original-46.json')
        y, s = (large['cores'][core] for core in ('YCore','SenPlayer'))
        rows.append(('第 46 轮 4K 原片', f"6.74 GB 的 MOV 中，YCore 和 SenPlayer 分别在 {y['openSeconds']:.2f} 秒和 {s['openSeconds']:.2f} 秒确认起播。两种手势各拖到 82% 和 21%，共八次确认。两内核均读取了超过 4 GiB 的文件偏移。"))
        links.append(('large-public-original-46.json','4K 原片字节校验、拖动目标和实际读取偏移'))
    for revision in (46, 50):
        name = f'module-power-long-{revision}.json'
        if not (ROOT / name).is_file():
            continue
        study = load(name)
        phases = study['phases']
        network = sum(p['idleFixture']['requests'] for p in phases)
        locks = sum(p['serviceSnapshots']['appAttributedPartialLockSnapshots'] for p in phases)
        location = sum(p['serviceSnapshots']['appNamedInLocationSnapshots'] for p in phases)
        rows.append((f'第 {revision} 轮八模块长测', f"每个场景观察 10 分钟，共 {study['samples']} 个采样点、{study['rawBatteryDumpCount']} 份系统电池快照。页面静置时测试源收到 {network} 次请求，{locks} 份快照记录到应用持有局部唤醒锁，{location} 份定位快照出现该应用。Wi-Fi 临时接收端在前台监听，退入后台后确认关闭。"))
        links.extend([(name, f'第 {revision} 轮各模块的采样及电量估算窗口'), (study['activityCurve'], '逐模块活动曲线图'),
                      (study['serviceCurvesCsv'], '逐样本系统计数 CSV'), (study['rawUidModelCsv'], 'UID 模型快照 CSV')])
    if (ROOT / 'ui-destinations-46.json').is_file():
        destinations = load('ui-destinations-46.json')
        rows.append(('第 46 轮页面核验', f"两套主题逐页走查，{destinations['verifiedPageDestinations']}/{destinations['pathThemeObservations']} 个页面配置都进入了预期目的地。308 张截图中有 5 张受系统保护，按控件树复核。深色大字号的空媒体库已正确显示。"))
        links.extend([('ui-destinations-46.json','逐页目的地验证'),('ui-review-46/overview.html','页面与滚动视口'),
                      ('ui-review-46/paired-index.json','浅色与深色大字号对照图索引')])
    if (ROOT / 'account-family-before-50.json').is_file():
        links.append(('account-family-before-50.json','双主题下接力入口、家庭资料和家长 PIN 的修复前证据'))
    if (ROOT / 'format-and-completion-46.json').is_file():
        rows.append(('第 46 轮多格式与片尾回拖', '每套内核均在 14 组格式和大小的文件中起播，分别通过 56 次拖动。四个自然播完的文件在两套内核上都完成回拖并恢复画面。'))
        links.append(('format-and-completion-46.json','14 组格式逐文件结果与片尾画面'))
    if (ROOT / 'full-host-regressions-48.json').is_file():
        host = load('full-host-regressions-48.json')
        rows.append(('第 48 轮完整主机回归', f"共 {host['suiteCount']} 个测试套件、{host['unique']:,} 项不同测试，全部通过；无跳过。"))
        links.extend([('full-host-regressions-48.json','完整主机回归的各套件结果'),('library-tab-capture-45.json','深色空媒体库误截与后续正确画面'),
                      ('private-relay-concurrency-43.json','连接失败时的源站请求并发数')])
    if (ROOT / 'charge-cold-start-43.json').is_file():
        cold = load('charge-cold-start-43.json')
        rows.append(('《Charge》冷启动', f"第 43 轮完整发送了 {cold['bytes']:,} 字节，SenPlayer 最后一次观察在 {cold['failureObservationHistory'][-1]['elapsed']:.1f} 秒，仍未取得进度条。此次未发出拖动。第 41 轮的两个内核及第 43 轮的 YCore 各通过同一文件的 120 次拖动。"))
        links.append(('charge-cold-start-43.json', '《Charge》的四组同文件对照及首次失败时间线'))
    for revision in (43, 46, 48, 49, 50):
        name = f'four-hour-verification-{revision}.json'
        if not (ROOT / name).is_file():
            continue
        study = load(name)
        for rec in study['results']:
            phases = {v['phase']:v for v in rec['phases']}
            cpu = phases['playing']['cpuMedianPercentOneGuestCore']
            cpu_text = f'CPU 中位数为单个模拟核心的 {cpu:.2f}%' if cpu is not None else '缺少可用的 CPU 中位数'
            model = rec['lastSystemUidModelMah']
            model_text = f'最后一次可用 UID 电池模型估算为 {model} mAh' if model is not None else 'Android 未提供该 UID 的电池模型估算'
            rows.append((f"第 {revision} 轮四小时 · {rec['core']}", f"历时 {rec['durationSeconds']/3600:.2f} 小时，{rec['samples']} 次采样。媒体库播放 35 分钟，{cpu_text}。从暂停位置恢复后验证了 {phases['seeking']['confirmedSeekActions']} 次拖动。{model_text}。"))
            rows.append((f"{rec['core']} 后台、熄屏与设置页", f"后台及熄屏静置时受控媒体流量分别增加 {phases['background-idle']['controlledBytesDuringSampledInterval']} 和 {phases['screen-off']['controlledBytesDuringSampledInterval']} 字节。同期媒体库分别收到 {phases['background-idle']['controlledLibraryRequestsDuringSampledInterval']} 和 {phases['screen-off']['controlledLibraryRequestsDuringSampledInterval']} 次 API 请求。设置页的十五分钟场景中，首末采样之间记录了 {phases['settings-idle']['serverInfoProbes']} 次服务器状态探测。"))
            links.extend([(rec['curveFile'],f"第 {revision} 轮 {rec['core']} 活动与电池模型曲线"),
                          (rec['curveCsv'],f"第 {revision} 轮 {rec['core']} 原始曲线 CSV")])
        links.extend([(name, f'第 {revision} 轮逐阶段核对记录'),
                      (f'activity-by-phase-{revision}.csv', f'第 {revision} 轮各阶段网络请求与 CPU 数据')])
        if (ROOT / f'uid-model-components-{revision}.png').is_file():
            links.append((f'uid-model-components-{revision}.png',f'第 {revision} 轮 Android 电池模型分项曲线'))
    if (ROOT / 'profile-health-baseline-43.json').is_file():
        links.append(('profile-health-baseline-43.json', '设置页一分钟探测及后台 API 请求的逐路由归类'))
    if (ROOT / 'private-sen-episodes-43.json').is_file():
        rows.append(('四集原片的 SenPlayer 对照', '四集的头、中、尾字节均与源站吻合。两集起播后共三次拖动未确认，另两集未在一分钟内确认画面连续推进。九条缓存代理警告中七条含有连接重置提示，9.69 GB 的 4K 剧集没有这类警告。现有记录无法确定连接在哪一端中断。'))
        links.append(('private-sen-episodes-43.json','四集的码率、画面时间线、缓存与连接记录'))
    if (ROOT / 'singularity-source-admission-46.json').is_file():
        rows.append(('《SINGULARITY》源站读取', '第 46 轮 SenPlayer 云机的源站读取连续两次超时，尚未调起播放器。同版本的 YCore 云机读到了原片，并完成两次拖动。第 41、43 轮将同一哈希的完整原片交给两个内核，各轮各内核均通过 120/120 次拖动。'))
        links.append(('singularity-source-admission-46.json','两台云机的实际读取及播放记录'))
    if (ROOT / 'subtitle-regressions-46.json').is_file():
        rows.append(('第 46 轮字幕', '两套内核分别测试了 2 秒和 598 秒的字幕。四组共 148 项检查通过，已独立核对 20 张实播画面中的字幕文字，覆盖重新选轨、返回前台和双字幕样式。'))
        links.append(('subtitle-regressions-46.json','二十张字幕截图的校验值与识别文字'))
    for revision in (47, 49, 50):
        name = f'arm-qualification-{revision}.json'
        if (ROOT / name).is_file():
            qual = load(name)
            rows.append((f'第 {revision} 轮 ARM 资格', f"{qual['hostUniqueTests']:,} 项不同主机用例和 {qual['hostRepeatedTests']} 次重复通过。ARM 云端模拟器通过 {qual['armPositiveCases']} 项验证，另有 {qual['oldApkNegativeCases']} 项旧包对照各自复现了预期故障。"))
            links.append((name, f'第 {revision} 轮 ARM 设备记录与旧版对照'))
    if (ROOT / 'foreground-range-recovery-49.json').is_file():
        recovery = load('foreground-range-recovery-49.json')
        repeat = recovery.get('repeatedLocalSocketVerification')
        extra = f"另有 {repeat['repeats']} 轮共 {repeat['realHttpTestCases']} 次真实 HTTP 对照通过。" if repeat else ''
        rows.append(('第 49 轮连续读的前台接手', f"第 48 轮随后在云端和本机都观察到同一位置先请求 128 KiB，再请求 2 MiB。修复前台接手逻辑后，第 49 轮 {recovery['fixedHostTests']:,} 项不同主机用例全部通过。{extra}云端资格、原片实播与正式签名分别核验。"))
        links.extend([('qualification-48/host-failures.json','第 48 轮云端首次失败的实际字节范围'),
                      ('foreground-range-recovery-49.json','新旧实现的两种内存条件实测'),
                      ('full-host-regressions-49.json','第 49 轮各主机测试套件')])
    if (ROOT / 'final-public-partial-46.json').is_file():
        direct = load('final-public-partial-46.json')['cores']
        for core, result in direct.items():
            rows.append((f'第 46 轮发布者原片 · {core}', f"{result['originalFilesAttempted']} 个文件中，{result['originalMovingOpens']} 个确认画面推进，{result['originalSeeksConfirmed']} 次拖动后恢复连续画面。"))
        links.append(('final-public-partial-46.json','88 个公共原文件的双内核起播与拖动结果'))
    if (ROOT / 'cached-thumbnail-verification-46.json').is_file():
        thumbnails = load('cached-thumbnail-verification-46.json')['cores']
        for core, result in thumbnails.items():
            requests = result['requestsWhileHeld']
            rows.append((f'第 46 轮缓冲缩略图 · {core}', f"热区显示、冷区隐藏、返回热区和抬手隐藏均有截图。按住期间额外出现 {requests['GET']} 次 GET、{requests['HEAD']} 次 HEAD。"))
        links.extend([('cached-thumbnail-verification-46.json','两内核真实按住阶段与截图校验'),
                      ('thumbnail-get-during-hold-46.json','SenPlayer 首次额外读取的源端计数')])
    if (ROOT / 'publisher-movie-trials-49.json').is_file():
        publisher = load('publisher-movie-trials-49.json')['cases']
        y = publisher.get('origin-ycore')
        if y:
            rows.append(('第 49 轮《Big Buck Bunny》发布者直连', f"YCore 的 {y['movingOpens']} 次起播确认了连续画面。已发出 {y['touchesIssued']} 次拖动，其中 {y['seeksWithMovingPicture']} 次确认目标位置继续播放。"))
        faults = publisher.get('timeout-candidate', {}).get('stalledReadTrials', [])
        if faults:
            recovered = sum(v['pictureConfirmed'] and v['exactSuffixRequests'] == 1 for v in faults)
            rows.append(('同一原片主动中断传输', f'SenPlayer 在 {recovered}/{len(faults)} 次实播中从第一个缺失字节继续读取，随后恢复画面。'))
        trials = publisher.get('latency-ycore-paired', {}).get('pairedStartupTrials', [])
        if trials:
            counts = []
            for build, label in [('previous', '旧版'), ('candidate', '修复版')]:
                pair = [t for t in trials if t['build'] == build]
                counts.append(f"{label} {sum(bool(t['startupPrefixThenFullBeyondFirstBlock']) for t in pair)}/{len(pair)} 次")
            rows.append(('YCore 在延迟片源上的重复读取', '起播时对首块以外同一位置先读短前缀、再读整块的次数为' + '，'.join(counts) + '。8 次起播及 16 次拖动均有连续画面，首帧中位数在两版均约 23 秒。'))
        links.append(('publisher-movie-trials-49.json','起播时间、实际拖动与断流续读偏移'))
    if (ROOT / 'iteration-50-verification.json').is_file():
        latest = load('iteration-50-verification.json')
        rows.append(('第 50 轮候选', latest['status']))
        links.extend([('iteration-50-verification.json','当前候选、云测任务与尚待验证项'),
                      ('full-host-regressions-50.json','手机和电视端主机测试结果'),('engine-source-scan-50.json','播放内核相关源码命中明细')])
        if (ROOT / 'private-verification-50.json').is_file():
            study = load('private-verification-50.json')
            rows[0] = ('本轮 32 部电影与 28 集剧集', study['conclusion'])
            y = study['afterSuccessfulRange']['YCore']
            drags = study['totals']['YCore']
            rows.append(('本轮 YCore 私人原片', f"设备能读取首段的 {y['files']} 部中，{y['movingInPrimaryWindow']} 部在起播观察期内确认画面连续推进，另有 {y['movingOnlyInExtraWindow']} 部在延长观察后确认。实际拖动 {drags['issuedSeeks']} 次，确认 {drags['confirmedSeeks']} 次。"))
            denied = study['androidRangeByEngine']['SenPlayer'].get('502', 0)
            rows.append(('本轮 SenPlayer 私人原片', f"设备独立读取首段时，{denied} 部均收到 HTTP 502，播放期间缺少可解码的数据。"))
            links.extend([('private-verification-50.json', '私人片源两端 HTTP 状态、时间与内核结果'),
                          ('private-original-files-50.csv','本轮 60 部原片逐部结果')])
        if (ROOT / 'engine-all-targets-50.json').is_file():
            audit = load('engine-all-targets-50.json')
            rows.append(('手机、电视、共享库与后端的内核排查', f"检查了 {sum(audit['textFilesByScope'].values()):,} 个文本文件。产品代码中的 8 行匹配来自旧偏好迁移、禁止旧内核的验证规则和 AndroidX 文本框生成的方法名。"))
            links.append(('engine-all-targets-50.json', '源码范围、按文件列出的匹配和审查依据'))
        if (ROOT / 'historical-1971-index.json').is_file():
            rows.append(('按历史目标逐项重放', '已将 1,971 个目标定位至 172 个测试文件的 383 组输入。两个内核各重放完整的 6,128 次手势，保留每个旧目标前后的操作；完成数以云端返回的连续画面记录为准。'))
            links.extend([('historical-1971-gestures.csv', '每次未确认拖动的目标秒数与旧观测'),
                          ('historical-1971-index.json', '旧记录哈希和分类依据')])
        if (ROOT / 'thumbnail-paired-origin-46.json').is_file():
            for core, trials in load('thumbnail-paired-origin-46.json')['engines'].items():
                cases = {trial['trial']: trial for trial in trials}
                hold, idle, paced = (cases[k] for k in ('held-stalled', 'idle-stalled', 'held-paced'))
                def requests(trial):
                    return trial['delta']['GET'] + trial['delta']['HEAD']
                rows.append((f'{core} 缓冲缩略图片源对照',
                             f"传输中断时拖动期间发出 {requests(hold)} 次请求，暂停静置 {idle['durationMs'] / 1000:.0f} 秒期间发出 {requests(idle)} 次。限速时拖动期间发出 {requests(paced)} 次请求，现有连接送入 {paced['delta']['bytes']:,} 字节。"))
            links.append(('thumbnail-paired-origin-46.json', '缩略图抓帧、每次片源请求与手指按下抬起的时间对照'))
        if (ROOT / 'original-seek-observation-ledger.json').is_file():
            links.append(('original-seek-observation-ledger.json', '公开原片按文件哈希、内核、相同手势与目标位置逐次对照'))
        if (ROOT / 'original-same-apk-control-46.json').is_file():
            control = load('original-same-apk-control-46.json')['cases']
            rows.append(('《Coffee Run》和 4K《Big Buck Bunny》原 APK 对照',
                         f"使用同一 APK、完整原片和相同拖动顺序，两个内核合计在目标处确认了 {sum(v['replayConfirmed'] for v in control)}/{sum(v['replayIssued'] for v in control)} 次连续画面。旧记录未确认的 {sum(len(v['nowConfirmedSameInput']) for v in control)} 个目标在本轮均通过。复测按原片逐帧时间戳比对。"))
            links.append(('original-same-apk-control-46.json', '同一 APK 的原片哈希、旧失败序号及复测结果'))
        if (ROOT / 'prepared-gesture-progress-46.json').is_file():
            partial = load('prepared-gesture-progress-46.json')
            counts = partial['totals']
            rows.append(('第 46 轮带帧码的电影与剧集片段',
                         '。'.join(f"{core} 已回收 {v['shards']}/16 组，已发出 {v['issued']:,} 次拖动，确认目标处连续画面 {v['confirmed']:,} 次" for core, v in counts.items()) + '。'))
            links.append(('prepared-gesture-progress-46.json', '逐组核对包、内核、目标及连续两帧的结果'))
    return '<h2>本轮原片、功耗与界面复验</h2>' + table(['项目','已核对的结果'], rows) + '<p>' + ' · '.join(
        f'<a href="{name}">{title}</a>' for name,title in links) + '。</p>'


def source_read_repairs():
    if not (ROOT / 'iteration-36-verification.json').exists():
        return ''
    newer = load('iteration-36-verification.json')
    retry = load('iteration-35-verification.json')
    rows = [('Caminandes 2 首次公开实播', 'SenPlayer 在片源返回第一段视频的响应头前收到 HTTP/2 REFUSED_STREAM。错误页显示“阶段未确定”，当次未发出拖动。YCore 同一片源已完成两次拖动。'),
            ('首次出现 HTTP/2 REFUSED_STREAM', '对同一字节范围重试一次，用户取消后停止请求。受控网络测试核对了第二次响应的内容及范围。'),
            ('连续拒流', '用当前片源的诊断显示“连接与读取”，提示稍后重试。后续读取成功时清理拒流记录。有明确 HTTP 权限错误时按该状态解释。'),
            ('本地主机测试', f"代理与错误分类两个套件 {newer['local']['passed']} 项通过。")]
    links = [('episode-retry-34/first-pass.json', '首次故障与分段读取记录'),
             ('iteration-35-verification.json', '首次重读修复'), ('iteration-36-verification.json', '持续拒流提示及验证')]
    for revision, detail in ((35, retry), (36, newer)):
        root = ROOT / f'qualification-{revision}'
        active = root / 'native-video-repair-contracts.json'
        report = root / 'repair-test-reports.json'
        reps = root / 'probe-cancellation-repeats.json'
        if active.is_file() and report.is_file() and reps.is_file():
            arm, host, repeat = (load(str(x.relative_to(ROOT))) for x in (active, report, reps))
            assert arm['success'] and set(arm['negativeControls'].values()) == {True}
            rows.append((f'第 {revision} 轮云端资格检查', f"{host['totals']['tests']:,} 项主机测试、{repeat['totals']['tests']} 项重复检查、{len(arm['tests'])} 项 ARM64 设备测试通过。旧版本的两个对照故障均已复现。"))
            if (root/'source-read-refused.png').is_file():
                links.append((f'qualification-{revision}/source-read-refused.png','云端网络读取故障页截图'))
    repeat_path = ROOT / 'episode-recovery-35.json'
    if repeat_path.is_file():
        real = load(repeat_path.name)
        for mode, data in real['routes'].items():
            passed = sum(row['seeksConfirmed'] for row in data['trials'])
            cold = sum(row['cache']['cleared'] and row['cache']['confirmed'] for row in data['trials'])
            kept = sum(not row['cache']['cleared'] and row['cache']['confirmed'] for row in data['trials'])
            rows.append(('原片复播 · ' + ('发布方地址' if mode=='origin' else '同文件回环对照'),
                         f"确认起播并完成两次拖动 {data['passedAttempts']}/12 轮，其中清空缓存 {cold}/6、保留缓存 {kept}/6，共确认 {passed}/24 次拖动。"))
        links.append((repeat_path.name, '逐轮缓存字节、取样时间和实际落点'))
    return ('<h2>剧集起播失败与重试</h2>' + table(['场景', '结果'], rows) + '<p>' +
            ' · '.join(f'<a href="{path}">{label}</a>' for path,label in links) + '。</p>')


def round34_evidence():
    candidate = load('iteration-34-verification.json')
    pkg = load('package-verification-34.json')
    assert candidate['source'] == pkg['source'] and candidate['packageVerification']['apkSha256'] == pkg['apkSha256']
    rows = [('Android 主机测试', f"{candidate['cloud']['host']['tests']:,} 项通过"),
            ('重复回归与 ARM64 设备', f"{candidate['cloud']['repeat']['tests']} 项重复检查、{candidate['cloud']['arm64Passed']} 项设备用例通过；两个旧版负对照均重现故障"),
            ('安装包', f"{pkg['versionName']}（{pkg['versionCode']}），云测签名。YCore 与 SenPlayer 原始资产通过核验，{pkg['dex']['methods']:,} 个含代码方法通过 DEX 检查，安装包与原生库通过 16 KiB 对齐校验")]
    links = [('iteration-34-verification.json', '本轮运行与用例记录'), ('package-verification-34.json', '安装包校验')]
    audio = ROOT / 'ui-review-34/audio-channel-repair.json'
    if audio.exists():
        d = load('ui-review-34/audio-channel-repair.json')
        assert d['apkSha256'] == pkg['apkSha256'] and d['verifiedSingleCountCards'] == 6
        rows.append(('电影、长标题与剧集详情', f"浅色及深色大字号共 6 张音频卡复核通过。原有的声道数重复行已经消失，保留数值的最低对比度为 {min(s['countContrast'] for s in d['scenes']):.2f}:1"))
        links.append(('ui-review-34/audio-channel-repair.json', '六张音频卡修复前后证据'))
        if (ROOT / 'ui-review-34/overview.html').exists():
            v = candidate['visual']
            rows.append(('页面与滚动', f"{v['routes']} 条页面路径、{v['routeVariants']} 种主题与字号配置、{v['captures']} 个视口。全部配置均已到达滚动终点。"))
            links.extend([('ui-review-34/overview.html', '逐页截图与交互记录'), ('ui-review-34/secure-backup-controls.json', '受保护备份页的五个按钮')])
    else:
        rows.append(('详情页音频卡', '此前 6 张卡都重复显示声道数。本轮已合并修复并通过单元测试，等待浅色及深色大字号截图复核。'))
    for filename, description in [('format-verification-34.json', '14 种格式实播'), ('final-public-partial-34.json', '公开原片与批量拖动')]:
        if not (ROOT / filename).exists():
            continue
        results = load(filename)
        assert results['apkSha256'] == pkg['apkSha256']
        for core, v in results['cores'].items():
            if 'fixtureCount' in results:
                summary = f"起播 {v['opened']}/{results['fixtureCount']}，确认拖动 {v['confirmedSeeks']}/{v['plannedSeeks']}"
            else:
                summary = f"已核对原片 {v['originalFilesCompleted']}/88，原片拖动 {v['originalSeeksConfirmed']}/176，时间码批量拖动 {v['confirmedSeeks']:,}/13,760"
            rows.append((f'{description} · {core}', summary))
        links.append((filename, description + '逐项证据'))
    if (ROOT / 'format-and-completion-34.json').exists():
        detailed = load('format-and-completion-34.json')
        for core, v in detailed['cores'].items():
            eof = v['completion']
            rows.append((f'自然结束后回拖 · {core}', f"{eof['naturalRewindsWithPicture']}/4 次恢复连续画面，片尾专项的 {eof['stressSeeksConfirmed']} 次拖动已核对"))
        links.append(('format-and-completion-34.json', '片尾与恢复播放记录'))
    if (ROOT / 'large-public-original-34.json').exists():
        big = load('large-public-original-34.json')
        assert big['apkSha256'] == pkg['apkSha256'] and big['file']['contentBytes'] == 6737592810
        for core, v in big['cores'].items():
            rows.append((f'《Tears of Steel》4K MOV · {core}',
                         f"同一份 {v['originalBytes']:,} 字节原文件在 {v['openSeconds']:.2f} 秒确认起播。普通拖动和按住预览均完成 82% 与 21% 的往返，前进目标的视频包偏移是 {v['packetOffsets']['0.82']:,} 字节。"))
        links.extend([('large-public-original-34.json', '4K 原片逐次落点、SHA-256 与读取证据'), ('https://mango.blender.org/download/', 'Blender 原片来源')])
    if (ROOT / 'episode-retry-34/first-pass.json').exists():
        rows.append(('公开剧集首轮失败', 'Caminandes 2 的源文件三处字节均能读取。SenPlayer 的首次读取收到 HTTP/2 REFUSED_STREAM，原生错误码为 −4。该次没有发出拖动。'))
        links.append(('episode-retry-34/first-pass.json', '起播失败证据'))
    return ('<h2>第 34 轮双内核候选</h2>' + table(['项目', '实测结果'], rows) + '<p>' +
            ' · '.join(f'<a href="{name}">{label}</a>' for name,label in links) + '</p>')


def round33_public_results():
    batch = load('final-public-partial-33.json')
    formats = load('format-and-completion-33.json')
    holds = load('cached-preview-33.json')
    ui = load('ui-review-33/index.json')
    assert formats['apkSha256'] == batch['apkSha256'] == holds['apkSha256'] == ui['apkSha256']
    rows = []
    for core, data in batch['cores'].items():
        fmt = formats['cores'][core]
        preview = holds['results'][core]
        rows.append((core, f"{data['originalFilesCompleted']}/88", f"{data['originalSeeksConfirmed']}/176",
                     f"{fmt['opened']}/11", f"{fmt['confirmedSeeks']}/44",
                     f"{fmt['completion']['naturalRewindsWithPicture']}/4", len(preview['phases']),
                     preview['requestsWhileHeld'], f"{data['confirmedSeeks']:,}/13,760"))
    return ('<h2>第 33 轮公开片源实播</h2><p>88 个原文件含 15 部电影与 73 集剧集，大小从 12,925,261 到 1,259,228,603 字节。'
            '另测 11 个专门的格式样本，含杜比视界 Profile 5、HEVC Main10、4K、ProRes 和长关键帧间隔。拖动恢复要求目标附近有两次独立画面变化，且播放位置前进。</p>' +
            table(['内核','原片起播','原片拖动','专项格式起播','专项拖动','片尾回拖','缩略图通过阶段','按住新增请求','批量已确认拖动'], rows) +
            '<p><a href="public-original-files-33.csv">88 个原文件的逐项结果</a> · <a href="format-and-completion-33.json">格式、拖动与片尾证据</a> · '
            '<a href="cached-preview-33.json">已缓冲和未缓冲位置的预览</a> · <a href="final-public-partial-33.json">批量拖动明细</a>。</p>'
            '<h3>页面与交互复核</h3><p>72 个页面以浅色和深色大字号走查 144 次，留下 303 个视口。'
            '三类详情页 60 处文字均达到 4.5:1。六张音频卡都重复显示了单声道数量，已登记为 UI-037。'
            '<a href="ui-review-33/overview.html">页面与滚动截图</a> · <a href="ui-review-33/file-info-cards.jpg">六张详情卡</a> · '
            '<a href="ui-review-33/audio-channel-duplicates.json">重复字段的无障碍树证据</a>。</p>')


def sampled_playback_activity():
    evidence = load('playback-cpu-30.json')
    rows = []
    for p in evidence['profiles']:
        if p['status'] != 'recorded':
            continue
        core = p['core']
        leading = list(p['libraryShares'].items())[:3]
        lead = '，'.join(f'{name} {percent:.2f}%' for name, percent in leading)
        rows.append((core, '预检后' if p['stage'] == 'pilot' else '四小时记录后',
                     p['durationSeconds'], lead, p['pictureAfterSampling']['laterPos']))
    paired = []
    for profile in load('playback-cpu-33.json')['profiles']:
        if profile['stage'] != 'pilot':
            continue
        windows = profile['outputSizeComparison']['windows']
        assert [w['screenshotSize'] for w in windows] == [[1280,720],[640,360],[640,360],[1280,720]]
        assert all(w['screenshotSize'] in w['surfaceViewSizes'] and w['mediaAdvanceSeconds'] > 0 for w in windows)
        paired.append([profile['core']] + [w['cpuPercentOneGuestCore']['com.yfuse'] for w in windows])
    return ('<h3>播放时 CPU 时间花在哪里</h3>'
            '<p>在四小时计时区间之外，对同一段 640×360、24 fps 的视频采样。'
            '下表百分比的分母为应用进程内采到的用户态 CPU 时间。两台模拟器的 EGL 驱动均报告 ANGLE。</p>' +
            table(['内核','采样时点','采样秒数','占比前三的库或未解析地址','采样后实播确认位置（秒）'], rows) +
            '<p>SenPlayer 预检的四个高占用线程在数学库内合计占 57.18%，在未解析地址合计占 37.61%。'
            '<a href="playback-cpu-30.json">逐线程与动态库数据</a>。</p>'
            '<p>第 33 轮固定测试片源，依次使用大、小、小、大四个显示窗口，每个窗口约 30 秒。每次核对实际屏幕与视频视图尺寸并检查前后画面。以下是应用占一个虚拟 CPU 核心的百分比。</p>' +
            table(['内核','1280×720 第一次','640×360 第一次','640×360 第二次','1280×720 第二次'], paired) +
            '<p>这轮同时改变了屏幕和视频视图大小。固定屏幕及字幕尺寸、仅改变视频缓冲的对照另行记录。'
            '<a href="playback-cpu-33.json">四个窗口的尺寸、应用及系统合成进程计数</a>。</p>' +
            fixed_display_study())


def fixed_display_study():
    name = 'sen-buffer-intervention-34.json'
    if not (ROOT / name).exists():
        return ''
    study = load(name)
    rows = []
    for transport, data in study['transports'].items():
        original = data['windows'][0]
        assert original['videoBufferWidth'] == 1280 and not data['passed']
        rows.append(('文件' if transport == 'file' else 'HTTP', f"{original['firstPictureSecond']}→{original['lastPictureSecond']}",
                     round(original['percentOfOneCore'], 2), data['sourceBytesSentByHttpServer'], '只占左下 640×360'))
    return ('<h3>固定 1280×720 屏幕，单独改动视频缓冲</h3>' +
            table(['片源','全屏 30 秒观测窗的画面时钟','全屏 CPU（单核 %）','本轮 HTTP 字节','缓冲改为 640×360 后的实拍结果'], rows) +
            embedded_image(ROOT / 'sen-buffer-comparison-34.png', '上排为全屏基线。下排为相同布局上只改动视频缓冲后的系统合成画面，只有左下四分之一显示了影片。') +
            '<p>两种片源在缩小缓冲后均未通过全屏画面检查，小缓冲阶段没有可用于节能比较的有效 CPU 读数。当前产品继续使用原来的全屏配置。'
            '<a href="sen-buffer-intervention-34.json">逐窗口数据与截图校验值</a>。</p>' +
            ('<p>随后将真实缓冲尺寸传给原生视口。在文件和 HTTP 两组的全屏对照中画面时钟均从 10 秒走至 40 秒，CPU 约 197.2%。'
             '缩小缓冲后，系统截图仍只有左下 640×360 有画面。该实验已结束。'
             '<a href="sen-buffer-viewport-trial-34.json">原生视口、画幅与色条位置记录</a>。</p>'
             if (ROOT / 'sen-buffer-viewport-trial-34.json').is_file() else ''))


def candidate_checks():
    candidate = load('iteration-28-verification.json')
    rows = [(r['variant'], r['steepestDropAtY'], r['sampleSpanPx'], r['maxLumaDrop'],
             r['after']['lumaDropAtSameCoordinates'])
            for r in candidate['captionBaseline']]
    public = load('final-public-partial-28.json')
    formats = load('format-and-completion-28.json')
    package = load('package-verification-28.json')
    controls = load('control-verification-28.json')
    assert all(data['source'] == candidate['source'] for data in (public, formats, package, controls))
    assert candidate['apkSha256'] == public['apkSha256'] == formats['apkSha256'] == package['apkSha256'] == controls['installedApkSha256']
    playback = []
    for core, p in public['cores'].items():
        fmt = formats['cores'][core]
        playback.append((core, f"{fmt['opened']}/10", f"{fmt['confirmedSeeks']}/40",
                         f"{p['cachedThumbnailPhasesPassed']}/7", p['requestsWhileHeld'],
                         f"{p['confirmedSeeks']:,} / 13,760", f"{p['originalFilesCompleted']} / {p['originalFilesAttempted']}",
                         f"{p['originalFilesCoveredAfterRechecks']} / 88"))
    return ('<h2>第 28 轮的双内核回归</h2><p>源码 <code>' + esc(candidate['source']) +
            '</code>。批量拖动逐次核对目标时间与连续画面。公开原片按文件统计，两次拖动都到达目标才计为通过。'
            '末列包括按相同片源与安装包复测通过的文件。</p>'
            + table(['内核','格式起播','格式拖动','缓冲缩略图阶段','按住期间新增请求','批量拖动通过 / 计划','公共原片首轮通过 / 已尝试','含复测覆盖 / 计划'], playback)
            + '<p>公开片源 Sintel 1080p MKV 的两个首轮任务在主机探测阶段结束。'
              '该次没有启动播放器，首轮和复测的结果分别保留。'
              '<a href="public-original-files-28.csv">逐文件规格、两内核首轮与复测 CSV</a> · '
              '<a href="final-public-partial-28.json">逐批计数和失败原因</a> · '
              '<a href="format-and-completion-28.json">十格式与片尾回拖</a> · '
              '<a href="control-verification-28.json">解码与故障恢复</a> · '
              '<a href="package-verification-28.json">云测安装包、内核及签名核验</a>。</p>'
            + '<h2>页面走查与封面修复</h2><p>两种显示配置共走查 72 条路径、144 个状态，均记录到滚动终点。'
              '已逐组查看 302 个视口的 35 张联系表。备份工具开启系统截图保护，该区域的视觉细节无法从截图核验。'
              '<a href="ui-review-28/overview.html">按页面打开原图与控件树</a>。</p>'
            + '<p>明亮海报与山景封面原先在标题上方出现深色横带。新版将渐变向上延伸最多 72 dp。'
              '在同一张山景图的相同坐标，取左右两侧避开文字的像素测量灰度下降幅度（0–255）。</p>'
            + table(['显示配置', '起点 y（像素）', '采样间距（像素）', '修复前', '修复后'], rows)
            + '<p>两个主题的八张海报均已逐张查看。旧版白底像素检查复现了明暗突变，修复版通过。'
              '<a href="discovery-review-28/light-discovery.jpg">浅色八张海报</a> · '
              '<a href="discovery-review-28/dark-large-discovery.jpg">深色八张海报</a> · '
              '<a href="discovery-review-28/light-library.jpg">浅色山景对照</a> · '
              '<a href="discovery-review-28/dark-large-library.jpg">深色山景对照</a> · '
              '<a href="iteration-28-verification.json">采样坐标与云测记录</a>。</p>'
            + '<p>弹窗专项覆盖 443 和 360 dp 两种屏宽，以及默认和 130% 字号。两种弹窗各四个状态均滚至末项并实际关闭。'
              '按钮始终保持 48 dp 的触控尺寸。<a href="motion-dialog-verification-27.json">八个状态的实测坐标</a>。</p>'
            + '<p>新版主机回归完成 ' + esc(candidate['hostTests']['repair-test-reports']['tests']) + ' 项。'
              '取消与竞争条件另重复 20 轮，完成 ' + esc(candidate['hostTests']['probe-cancellation-repeats']['tests'])
            + ' 项，两组均无跳过或失败。<a href="repair-test-reports-28.json">主机测试明细</a> · '
              '<a href="probe-cancellation-repeats-28.json">重复测试明细</a>。</p>')


def private_originals_28():
    result = load('private-verification-28.json')
    rows = []
    for core, kinds in result['byKind'].items():
        for kind, item in kinds.items():
            rows.append((core, '电影' if kind == 'Movie' else '剧集',
                         f"{item['movingOpen']}/{item['attempted']}", item['issuedSeeks'],
                         item['confirmedSeeks'], item['attempted'] * 2))
    return ('<h2>60 个 Emby 原片的逐片实播结果</h2><p>源码 <code>' + esc(result['source']) +
            '</code>。每个文件先核对开头、中段、末尾三个位置，再由两套内核独立打开。'
            '起播须看到选中内核输出连续画面。未起播的文件保留在计划拖动次数中。</p>' +
            table(['内核','类别','持续画面 / 已尝试','实际发出拖动','画面到达目标','计划拖动'], rows) +
            '<p>180 个位置的源端与云端字节全部匹配。YCore 的 23 个原片记录了平台解码器输出异常，'
            '其中 22 个未在主测试窗口确认起播。八个请求杜比视界的文件中，六个为 Profile 5，'
            '另两部为 Profile 7 和 8.1。各类异常可能发生在同一文件。</p>'
            '<p>SenPlayer 上次的六处电影拖动本轮均已恢复。本轮一集剧集未起播，'
            '它的 Android 分段检查未返回 HTTP 状态。YCore 的四次拖动失败中，三次在单独重发手势后恢复，'
            '原始结果按首次尝试保留。两台测试机已清理，服务器临时端口已关闭。</p>'
            '<p>YCore 的 38 次未起播可按首要观测分为五组。直播放出首帧前解码器拒绝输出 22 个，'
            '增强路径拒绝视频输出 2 个，杜比视界配置 8 个，原生读取或探测超时 4 个，'
            '其余 2 个在主观察窗口内有读取活动但未显示可辨认画面。</p>'
            '<p><a href="private-original-files-28.csv">60 个文件的格式、大小与拖动结果</a> · '
            '<a href="private-failure-scenarios-28.csv">逐片未起播或拖动失败的归类</a> · '
            '<a href="private-originals-28-by-file.json">逐片证据及前后对照</a> · '
            '<a href="private-verification-28.json">解码器记录与格式分类</a> · '
            '<a href="../legacy-engine-20261113/integrated-20261116/relay-cleanup-44250408246.json">流量与清理记录</a>。</p>')


def private_repair_progress():
    first, base = load('iteration-29-verification.json'), load('iteration-30-verification.json')
    local = base['local']
    return ('<h2>私人原片失败后的修复</h2><p>YCore 在首帧输出失败后会恢复到用户实际请求的位置。'
            '第 29 轮的 ARM64 设备检查通过了 ' + esc(first['qualification']['devicePassed']) +
            ' 项，其中包含已开始播放后的重新定位。集成测试首次有一项剧集队列用例在 5 秒后超时；'
            '该源码再次完整运行通过。该用例在本机原样重复 100 次均通过，首次超时的具体调度顺序无法追溯。'
            '<a href="iteration-29-verification.json">第 29 轮资格检查与失败记录</a>。</p>'
            '<p>第 30 轮增加了从已确认的 HEVC 兼容基础层解码到 SDR 的路径。为检测帧内容或位置改变，'
            '设备用例逐帧比对六幅合成图像的 RGBA 摘要与时间戳。首次比对在读取参考片时发现，'
            '编码器没有写入 PQ 传递函数。修正后，主机逐帧确认了 BT.2020 与 PQ 标记。'
            '同一候选已通过 ' + esc(base['cloud']['host']['tests']) + ' 项主机回归和 ' +
            esc(base['cloud']['repeat']['tests']) + ' 次重复检查。ARM64 的 ' + esc(base['cloud']['arm64Passed']) +
            ' 项设备用例全部通过，六帧颜色与时间戳逐一匹配，旧版进度条与封面对照均复现原问题。'
            '<a href="qualification-30/native-video-repair-contracts.json">逐项设备结果与旧版对照</a>。'
            '本机已完成 ' + esc(local['results']['tests']) + ' 项聚焦回归。剧集状态测试整组另外执行了 ' +
            esc(local['episodeQueueRepeatedSuites']) + ' 轮，每轮 ' + esc(local['perSuiteCases']) + ' 项。'
            '<a href="iteration-30-verification.json">第 30 轮状态、测试与样本来源</a>。</p>')


def recent_checks():
    formats = load('format-and-completion-27.json')
    public = load('final-public-partial-27.json')
    iteration = load('iteration-27-verification.json')
    package = load('package-verification-27.json')
    assert formats['source'] == public['source'] == iteration['playbackSource']
    assert formats['apkSha256'] == public['apkSha256'] == iteration['playbackApkSha256']
    assert package['apkSha256'] == formats['apkSha256'] and not package['formalRelease']
    results = []
    for core, fmt in formats['cores'].items():
        p = public['cores'][core]
        results.append((core, f"{fmt['opened']}/10", f"{fmt['confirmedSeeks']}/40",
                        f"{fmt['completion']['stressSeeksConfirmed']}/16", f"{fmt['completion']['naturalRewindsWithPicture']}/4",
                        f"{p['cachedThumbnailPhasesPassed']}/7", p['requestsWhileHeld'],p['confirmedSeeks'],
                        f"{p['originalFilesCompleted']}/{p['originalFilesAttempted']}"))
    rows = table(['内核','十格式起播','格式拖动','片尾交界拖动','自然结束后回拖','缩略图阶段','悬停新增请求','已核对批量拖动','已返回原片中通过'], results)
    return ('<h2>上一版完整的双内核播放回归</h2><p>被测源码 <code>' + esc(formats['source']) + '</code>。'
            '十个合成格式样本覆盖 4K HEVC、Main 10、ProRes 4444、AVC 传输流和超三小时长片。两套内核使用相同文件，每次拖动均核对目标时间与连续画面。末列记录直接播放公共电影和剧集原文件的结果。</p>' + rows +
            '<p>YCore 在四个自然结束样本上回拖至指定位置，保持暂停，点击播放后均恢复画面。SenPlayer 在四个样本上均可从片尾回拖。'
            '批量数据仅统计已下载并校验的分片。<a href="format-and-completion-27.json">十格式与片尾逐项记录</a> · '
            '<a href="final-public-partial-27.json">公共原片与批量拖动记录</a> · '
            '<a href="public-original-files-27.csv">按原文件查看大小、音视频格式及双内核结果（CSV）</a>。</p>'
            '<p>这一 APK 已核对两套内核和五项 SenPlayer 原始资产，版本 1.9.6（348）。92,511 个含代码的方法通过 DEX 检查。原生库和安装包均通过 16 KiB 对齐核验，使用云测签名。'
            '<a href="package-verification-27.json">APK 校验记录</a> · <a href="control-verification-27.json">解码、软件输出和错误恢复的 96 项检查</a>。</p>'
            '<h3>设置弹窗与剧集界面</h3><p>连续两轮覆盖 72 条页面路径的浅色和深色大字号状态。第 26 轮记录 303 个视口，第 27 轮记录 307 个。两轮的 144 个页面状态均观察到滚动终点，各有 143 个通过交互检查。'
            '剧集编号与单服务器空分组已在两种主题下复核。深色大字号下，滚动后的粒子光效关闭按钮可访问高度由 78 降至 69 像素，截图中的标题和图标保持原位。'
            '<a href="ui-review-27/overview.html">第 27 轮逐页截图与控件树</a> · <a href="ui-review-26/overview.html">第 26 轮对照</a> · <a href="iteration-27-verification.json">修复版本与回归运行</a>。</p>')


def phase_model_section(records, round_no):
    if not all('uidModelIncreaseMah' in phase for r in records for phase in r['phases']):
        return ''
    names = {'stopped-control':'停止进程对照','servers-idle':'服务器页静置','library-idle':'媒体库静置',
             'settings-idle':'设置页静置','settings-interaction':'设置交互','playing':'播放','paused':'暂停',
             'seeking':'拖动','after-player-idle':'退出播放器','background-idle':'后台静置','screen-off':'熄屏',
             'network-failure':'网络中断','recovered-playback':'重新播放'}
    first, second = records
    rows = []
    for left, right in zip(first['phases'], second['phases'], strict=True):
        assert left['phase'] == right['phase']
        rows.append((names[left['phase']], left['modelWindowSeconds'], left['uidModelIncreaseMah'],
                     right['modelWindowSeconds'], right['uidModelIncreaseMah']))
    headings = ['功能场景', first['core'] + ' 采样跨度（秒）', first['core'] + ' 估算增量（mAh）',
                second['core'] + ' 采样跨度（秒）', second['core'] + ' 估算增量（mAh）']
    output = ('<h3>各功能场景的系统电量估算</h3><p>每格取该阶段 Android 首末两次有效 UID 模型读数的差，'
              '显示器、音频、视频的独立模型分项保存在 CSV。缺少读数或计数重置的格子留为不可测。'
              '模拟电池固定电量，读数精度和系统刷新频率都会影响增量。</p>'
              + table(headings, rows) + f'<p><a href="activity-by-phase-{round_no}.csv">下载 13 个场景的模型分项与活动数据</a>。</p>')
    threads = []
    links = []
    for record in records:
        details = record.get('threadCpu')
        if not details:
            continue
        links.append(f'<a href="{esc(details["csv"])}">{esc(record["core"])} 逐区间线程 CSV</a>')
        for phase in ('playing','paused','library-idle','background-idle','screen-off'):
            sample = details['phases'].get(phase, {})
            for thread in sample.get('highestByMeasuredCpuTime', [])[:3]:
                threads.append((record['core'], names[phase], thread['name'], thread['matchedSeconds'],
                                thread['cpuSeconds'], thread['percentOfOneGuestCoreWhenMatched']))
    if links:
        output += ('<h3>持续运行的线程</h3><p>逐线程匹配同一进程、线程号、启动时间和线程名。'
                   '下表显示安静区间内累计 CPU 时间最多的三个线程名。同名线程合计，百分比按一个虚拟核计算。'
                   '两次采样之间创建并退出的线程未计入。</p>'
                   + table(['内核','场景','Android 线程名','匹配区间（秒）','累计 CPU 秒','匹配期间 CPU %'], threads)
                   + '<p>' + ' · '.join(links) + '。</p>')
    return output


def model_components_section(verification):
    round_no = int(Path(verification['evidence']).stem.rsplit('-', 1)[1])
    index = f'uid-model-components-{round_no}.json'
    if not (ROOT / index).exists():
        return ''
    data = load(index)
    overview = []
    for core, item in data['engines'].items():
        amounts = item['lastModelMah']
        overview.append((core, item['snapshots'], amounts['total'], amounts['screen'], amounts['audio'],
                         '系统未报告' if amounts['video'] is None else format(amounts['video'], '.7f')))
    graph = f'uid-model-components-{round_no}.png'
    csv_name = f'uid-model-by-phase-{round_no}.csv'
    return (f'<h3>各场景的电池模型分项（第 {round_no} 轮）</h3>' +
            table(['内核', '系统快照', '累计 UID 模型 mAh', '屏幕 mAh', '音频 mAh', '视频 mAh'], overview) +
            '<p>每个场景的增量按该阶段首末两个系统快照计算。表格空白表示系统未给出可相减的两个读数。CPU、定位、网络轮询和唤醒另有活动采样。'
            f'<a href="{csv_name}">全部 26 行场景数据</a> · <a href="{index}">分项可用性与原始快照数</a>。</p>'
            f'<details><summary>查看两种内核的模型分项曲线</summary><img loading="lazy" src="{graph}" alt="四小时内按功能场景排列的屏幕、音频及可用视频估算曲线"></details>')


def current_activity_section(verification):
    source = verification['source']
    filename = verification['evidence']
    round_no = int(Path(filename).stem.rsplit('-', 1)[1])
    records = [r for r in load(filename)['results'] if r['source'] == source and r['verified']]
    assert len(records) == 2 and len({r['apkSha256'] for r in records}) == 1
    overview, idle, pause, snapshots = [], [], [], []
    for record in records:
        phases = {p['phase']: p for p in record['phases']}
        core = record['core']
        overview.append((core, round(record['durationSeconds']/60, 2), record['samples'], len(phases),
                         phases['playing']['cpuMedianPercentOneGuestCore'], phases['paused']['cpuMedianPercentOneGuestCore'],
                         record['lastSystemUidModelMah'], ', '.join(str(v) for v in record['simulatedBatteryLevels'])))
        for phase, label in [('library-idle', '媒体库静置'), ('background-idle', '后台'), ('screen-off', '熄屏')]:
            entry = phases[phase]
            idle.append((core, label, round(entry['seconds']/60, 1), entry['cpuMedianPercentOneGuestCore'],
                         entry['uiFramesPerSecondOutsideInterventions'], entry['controlledBytesDuringSampledInterval']))
        p = phases['paused']
        pause.append((core, round(p['controlledBytesDuringSampledInterval']/1e6, 2),
                      p['lastMediaByteChangeAtSampleSeconds'], p['quietMediaTailSeconds'],
                      phases['seeking']['confirmedSeekActions']))
        snapshots.append((core, sum(p['locationSnapshots'] for p in phases.values()),
                          sum(p['appNamedInLocationSnapshots'] for p in phases.values()),
                          sum(phases[k]['appAttributedPartialLockSnapshots'] for k in ['background-idle', 'screen-off']),
                          sum(phases[k]['wakeLockSnapshots'] for k in ['background-idle', 'screen-off'])))
    prefix = f'long-curves/power-final-{round_no}-power-baseline-'
    return ('<h2>已核验版本的四小时记录</h2><p>被测源码 <code>' + esc(source) + '</code>。</p>' +
            table(['内核', '记录分钟', '采样点', '通过场景', '播放 CPU 中位数 %', '暂停 CPU 中位数 %', 'UID 模型累计 mAh', '模拟电池百分比'], overview) +
            '<p>CPU 按一个虚拟核计。电池百分比来自模拟器设定，UID 数据来自 Android 系统估算。物理放电量在本环境中无法测量。</p>' +
            table(['内核', '场景', '分钟', 'CPU 中位数 %', 'UI 帧/秒', '受控媒体新增字节'], idle) +
            table(['内核', '暂停预读 MB', '暂停后最近一次增加（秒）', '末段连续无新增（秒）', '交替定位确认次数'], pause) +
            '<p>两内核实际收到断网错误，恢复连接后均再次确认连续画面。暂停预读按该阶段首末采样的差值计算。</p>' +
            table(['内核', '有效定位快照', '出现应用的快照', '后台和熄屏归属该应用的部分唤醒锁快照', '后台和熄屏有效唤醒锁快照'], snapshots) +
            '<p>快照覆盖采样时刻，可从原始数据核对 CPU、受控字节、UI 帧率和系统模型读数。' +
            f'<a href="activity-by-phase-{round_no}.csv">分阶段数据 CSV</a> · <a href="{esc(filename)}">逐阶段核对记录</a> · ' +
            f'<a href="{prefix}YCore-power.png">YCore 曲线</a> · <a href="{prefix}SenPlayer-power.png">SenPlayer 曲线</a>。</p>'
            + phase_model_section(records, round_no))


UI_COPY = {
    'UI-001': ('动效主题行的左右留白与其他行不一致', '统一为横向16dp、纵向11dp，保留整行点击范围'),
    'UI-002': ('长设置页滚动后丢失标题及返回入口', '将页标题固定，保留原有搜索定位索引'),
    'UI-003': ('空服务器页面显示无可操作对象的工具和重复入口', '保留空状态主操作，已有服务器时继续显示必要工具'),
    'UI-C04': ('Trakt 返回按钮疑似缺失', '全分辨率截图确认存在；撤销仅凭缩略联系表作出的判断'),
    'UI-004': ('明亮视频上播放控制文字对比不足', '加强控制层渐变遮罩和字幕文字不透明度'),
    'UI-005': ('解码状态暴露内部路径名称，部分入口显示不一致', '统一播放器及更多菜单的中文解码状态'),
    'UI-006': ('媒体库标题与工具同排，容易被挤压省略', '标题允许两行，工具独立成行'),
    'UI-007': ('明亮海报上的简介文字难辨认', '在简介区域添加局部深色遮罩并提高文字对比'),
    'UI-008': ('分类首次异步加载后偏到列表末端，第一张卡片被裁切', '仅首次从空数据变为有分类时回到首项；后续刷新保留手动位置'),
    'UI-009': ('SenPlayer 媒体诊断中显示不适用的 YCore 管线', '按实际内核显示相关诊断字段'),
    'UI-010': ('播放器深层设置沿用上一页滚动位置，标题和返回被隐藏', '切换菜单目标时回到页首，并对实际设置面板执行导航回归'),
    'UI-011': ('小屏播放器的中央播放键遮挡设置面板', '面板或对话框打开时隐藏中央播放操作和手势提示'),
    'UI-012': ('大字体长标题与详情页返回、更多按钮重叠', '按实际标题高度增加头图空间，预留系统栏与顶部操作区'),
    'UI-013': ('明亮视频透过手势帮助窗口，干扰文字阅读', '手势帮助采用不透明底色及对应的文字配色'),
    'UI-014': ('选项说明被两行省略号截断，条件信息不完整', '说明文字完整换行，并保留弹窗内滚动'),
    'UI-015': ('明亮详情封面使标题和辅助信息难以辨认', '按文字实际高度添加局部渐变底色'),
    'UI-016': ('小于1MB的导入文件被显示成0MB', '根据实际大小显示B、KB、MB或GB'),
    'UI-017': ('播放信息包含冗长MIME标识且大小写不统一', '转为H.264、HEVC等简明编码名称并统一SDR/HDR'),
    'UI-018': ('聚合媒体库深色标题和浅色状态栏图标对比不足', '标题和状态栏图标显式跟随当前主题'),
    'UI-019': ('SCore 使用缺乏解码依据的硬解功耗估计', '实际内核没有提供功耗依据时明确显示未评估'),
    'UI-020': ('登录设备长列表滚动后标题、返回与刷新入口消失', '将标题与操作固定在可滚动内容上方'),
    'UI-021': ('不可用的会话批量退出操作看起来仍可点击', '弱化空列表及执行中不可用操作的视觉状态，保留禁用语义'),
    'UI-022': ('后台声音说明未交代当前播放方式的能力限制', '明确支持时继续声音、不支持时暂停并在返回后提示'),
    'UI-023': ('预计结束时间紧贴屏幕右边缘', '进度行添加左右12dp留白，保留对称刘海安全区'),

}


def build_power_inventory():
    records = []
    phases = []
    artifact_entries = set()
    for source in sorted(EVIDENCE.rglob('summary.json')):
        sample_path = source.with_name('samples.jsonl')
        if not sample_path.exists():
            continue
        summary = json.loads(source.read_text(encoding='utf-8'))
        condition_file = source.with_name('conditions.json')
        protocol = json.loads(condition_file.read_text(encoding='utf-8')).get('protocol') if condition_file.exists() else None
        if 'physicalBatteryConsumptionAvailable' not in summary:
            continue
        receipt = source.parent.parent / 'download-receipt.json'
        if receipt.is_file():
            artifact_id = json.loads(receipt.read_text(encoding='utf-8')).get('id')
            key = artifact_id, source.relative_to(receipt.parent)
            if artifact_id is not None and key in artifact_entries:
                continue
            artifact_entries.add(key)
        samples = [json.loads(line) for line in sample_path.read_text(encoding='utf-8').splitlines() if line]
        relative = source.relative_to(EVIDENCE)
        source_sha = None
        provenance = source.parent.parent / 'provenance.txt'
        if provenance.exists():
            source_sha = next((line[4:] for line in provenance.read_text().splitlines() if line.startswith('app=')), None)
        run = {
            'evidence': str(relative), 'source': source_sha, 'samples': len(samples),
            'seconds': summary.get('durationSeconds'), 'status': summary.get('status'),
            'pilot': 'pilot' in str(relative), 'physicalEnergyMah': None,
            'batteryModel': 'simulated; charge percentage is not measured discharge',
            'phaseCount': len(summary.get('phases', [])),
            'protocol': protocol,
        }
        records.append(run)
        for phase in summary.get('phases', []):
            name = phase if isinstance(phase, str) else phase['phase']
            rows = [row for row in samples if row['phase'] == name]
            if isinstance(phase, str):
                phase = {'status': 'sampled' if rows else 'unobserved', 'measuredSeconds':
                         round(rows[-1]['seconds'] - rows[0]['seconds'], 3) if len(rows) >= 2 else None}
            quiet = [row['cpuPercentOneCore'] for row in rows if
                     not row.get('intervalIncludesIntervention', row.get('intervention')) and
                     row.get('cpuPercentOneCore') is not None]
            import statistics
            phases.append({
                'study': str(relative.parent), 'source': source_sha, 'phase': name,
                'protocol': protocol,
                'networkOutageQualified': name == 'network-failure' and protocol == 2 and phase.get('status') == 'recorded',
                'status': phase.get('status'), 'measuredSeconds': phase.get('measuredSeconds'),
                'samples': len(rows), 'quietCpuSamples': len(quiet),
                'quietCpuMedianPercentOneGuestCore': statistics.median(quiet) if quiet else None,
                'diagnosticMemorySamples': phase.get('memorySamplesWhileInPhase'),
                'physicalEnergyMah': None,
            })
    (ROOT / 'power-recording-index.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
    if phases:
        with (ROOT / 'power-phase-results.csv').open('w', encoding='utf-8-sig', newline='') as output:
            writer = csv.DictWriter(output, fieldnames=list(phases[0]))
            writer.writeheader()
            writer.writerows(phases)
    return records


def main():
    progress, ui, power = load('progress.json'), load('ui-issues.json'), load('power-issues.json')
    records = build_power_inventory()
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds')
    completed_hours = sum(row['seconds'] or 0 for row in records if row['status'] == 'recorded' and not row['pilot']) / 3600
    ui_rows = [(i['id'], i.get('severity'), *UI_COPY.get(i['id'], (i.get('finding'), i.get('fix'))), status_label(i)) for i in ui['issues']]
    with (ROOT / 'ui-fix-register.csv').open('w', encoding='utf-8-sig', newline='') as output:
        writer = csv.writer(output)
        writer.writerow(['编号', '级别', '问题', '修改', '验证状态', '提交'])
        for row, issue in zip(ui_rows, ui['issues']):
            writer.writerow([*row, issue.get('commit', issue.get('source', ''))])
    power_copy = {
        'PWR-001': ('后台诊断轮询', '暂停或离开前台后，进程内存诊断仍每10秒执行', '仅在实际播放且应用位于前台时保留采样'),
        'PWR-002': ('后台界面计时', '缓存媒体库的更新时间提示每60秒刷新，原先没有随应用进入后台而停止', '在生命周期停止或离开该页时取消计时；返回时立即刷新'),
        'PWR-003': ('无订阅的提醒调度', '没有手动启用追剧提醒，仍注册15分钟提醒任务', '取消空订阅的周期、即时任务及闹钟；订阅后恢复，并覆盖Android13以下系统'),
        'PWR-004': ('内容加载后的持续刷新', '媒体库和搜索页加载完成后仍订阅骨架动画时钟', '仅在加载或必要的最短展示期间订阅，完成后停止刷新'),
        'PWR-005': ('后台画面生命周期', 'SCore 拒绝空 Surface 后正常切后台被标记为播放失败', '先暂停；仅对该明确拒绝释放会话，返回前台后按保留位置重建'),
        'PWR-006': ('保留页面的后台计时', '服务器卡片、播放器文字时钟及接力页额外刷新未完整跟随Activity停止', '页面可见且Activity至少STARTED时运行，隐藏或退后台时取消，返回后立即刷新'),
    }
    power_rows = [(i['id'], i.get('severity'), *power_copy.get(i['id'], (i.get('category'), i.get('cause', i.get('finding')), i.get('change', i.get('fix')))), status_label(i)) for i in power['issues']]
    iteration_rows = []
    for i in progress['iterations']:
        iteration_rows.append((i.get('round', i.get('number')), i.get('typeZh', i.get('type', '界面与生命周期修复')),
                               i.get('changesZh', '；'.join(i.get('changes', [])) or i.get('fix', i.get('result', '—'))),
                               i.get('validationZh', i.get('validation', i.get('verification', i.get('longTermVerification', '—'))))))
    recordings_table = table(['证据目录', '源码提交', '采样点', '实际分钟', '阶段数', '状态'],
                             [(r['evidence'], (r['source'] or '')[:12], r['samples'],
                               round((r['seconds'] or 0) / 60, 2), r['phaseCount'], r['status']) for r in records])
    partial_volume = progress.get('completedRegression', {}).get('partialVolume', {})
    seek_rows = [(name, partial_volume.get(name, {}).get('verified'), partial_volume.get(name, {}).get('shards'))
                 for name in ['YCore', 'SenPlayer']]
    current_public = load('final-public-partial-19.json')
    candidate_public = load('final-public-partial-24.json') if (ROOT / 'final-public-partial-24.json').exists() else None
    candidate_playback = ''
    if candidate_public:
        candidate_rows = [(core, data['confirmedSeeks'], len(data['completedVolumeShards']),
                           str(data['originalFilesCompleted']) + '/' + str(data['originalFilesAttempted']),
                           data['originalSeeksConfirmed'], data['requestsWhileHeld'])
                          for core, data in candidate_public['cores'].items()]
        candidate_playback = '<h2>已完成批量播放测试的版本</h2>' + table(
            ['内核','已核对批量拖动','已核对分片','公共原片首轮','首轮拖动确认','悬停时新请求'], candidate_rows)
        candidate_playback += ('<p>源码 <code>' + esc(candidate_public['source']) + '</code>；这里只统计已下载并核对的结果，'
                               '等待中或尚未返回的分片不算通过。<a href="final-public-partial-24.json">当前逐项汇总</a>。</p>')
        coverage_rows = []
        for core, data in candidate_public['cores'].items():
            if data['preparedClipUniqueWorks']:
                coverage_rows.append((core, data['preparedClipUniqueWorks'],
                    f"{data['preparedClipBytesMin']/1e6:.2f}–{data['preparedClipBytesMax']/1e6:.2f} MB",
                    ', '.join(data['preparedClipVideoCodecs']), ', '.join(data['preparedClipAudioCodecs'])))
        if coverage_rows:
            candidate_playback += table(['内核', '已验证样本作品数', '转制样本大小', '视频编码', '音频编码'], coverage_rows)
            clip_min = min(c['preparedClipSecondsMin'] for c in candidate_public['cores'].values() if c['preparedClipUniqueWorks'])
            clip_max = max(c['preparedClipSecondsMax'] for c in candidate_public['cores'].values() if c['preparedClipUniqueWorks'])
            candidate_playback += f'<p>批量拖动使用不同电影与剧集转制的 {clip_min}–{clip_max} 秒样本，以画面时间码验证落点。公共原片直接播放原文件，大小约 12.93 MB–1.26 GB。'
            candidate_playback += '两组样本有作品重叠。超长片、4K 和私人原片的逐片结果另外列出。</p>'
        for core, data in candidate_public['cores'].items():
            for recheck in data.get('originalRechecks', []):
                candidate_playback += ('<p>' + esc(core) + ' 原失败分组复测：' + str(recheck['completedFiles']) +
                                       '个文件、' + str(recheck['confirmedSeeks']) + '次拖动通过；合计覆盖' +
                                       str(data['originalFilesCoveredAfterRechecks']) +
                                       '个不同原片。首轮DNS失败仍保留，复测不改写首轮成功率。'
                                       '<a href="dns-verification-24.json">原地址与同字节对照</a>。</p>')
        controls = load('control-verification-24.json')
        candidate_playback += '<p>相同源码的设备专项：解码42/42、软件输出18/18、错误后恢复36/36；真实进度条取消与缓存字节解码3/3。'
        candidate_playback += '两内核分别完成5轮HTTP403拒绝后同进程恢复，均确认画面与时间继续推进。'
        candidate_playback += '<a href="control-verification-24.json">专项逐项记录</a>。该结果不替代私人原片兼容性测试。</p>'
        assert [r['verified'] for r in controls['results']] == [42, 18, 36]
        assert controls['actualRailCancel']['passed'] == 3
        first_matrix = load('format-full-first-25.json') if (ROOT / 'format-full-first-25.json').is_file() else None
        if first_matrix and first_matrix['source'] == candidate_public['source']:
            candidate_playback += '<h3>完整格式矩阵</h3><p>同一 APK 回放十个逐文件校验过的样本，覆盖 ProRes 4444 12 位与 PCM 24 位、AVC 传输流、HEVC Main 10、4K 和超过三小时的长片。'
            candidate_playback += '首轮两内核各打开 10/10，YCore 拖动 40/40，SenPlayer 拖动 38/40。两次未确认均为 20 秒 4K 短片的 63% 落点。<a href="format-full-first-25.json">首次逐样本结果</a>。</p>'
            if (ROOT / 'format-full-recheck-25.json').is_file():
                retest = load('format-full-recheck-25.json')
                candidate_playback += table(['内核', '复测打开', '复测拖动确认', '按住时轨道宽度保持不变'], [
                    (core, str(v['opened']) + '/10', str(v['confirmedSeeks']) + '/40', str(v['stableHeldRails']) + '/20')
                    for core, v in retest['cores'].items()])
                candidate_playback += '<p>短片松手后在设备内连续采集屏幕时钟，并比对两次独立画面变化。<a href="format-full-recheck-25.json">复测逐帧记录</a>。</p>'
        package = load('package-verification-24.json')
        assert package['apkSha256'] == candidate_public['apkSha256']
        candidate_playback += '<p>同一云测APK静态检查：YCore库与5项SenPlayer原始资产校验通过，未含独立旧内核；92,506个DEX方法无检查异常，ELF及ZIP的16 KiB对齐通过。'
        candidate_playback += '第 24 轮云测 APK 为 1.9.6（348），使用测试签名。<a href="package-verification-24.json">该轮安装包证据</a>。</p>'
        thumbnail_root = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116/final-public-24'
        candidate_playback += '<details><summary>当前双内核缓存缩略图：已缓存显示，未缓存隐藏</summary>'
        for core in ['YCore', 'SenPlayer']:
            candidate_playback += '<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px">'
            for mode, caption in [('held-hot', '已缓存位置的实际解码缩略图'), ('held-cold', '未缓存位置不显示缩略图')]:
                candidate_playback += embedded_image(thumbnail_root / ('cached-holds-' + core) / (mode + '.png'), core + '：' + caption)
            candidate_playback += '</div>'
        candidate_playback += '<p>同一按住手势从缓存内移到缓存外再返回；两内核各7阶段通过，悬停期间新增请求为0。松手后的正常播放取数独立记录。</p></details>'
    current_rows = [(name, data['confirmedSeeks'], len(data['completedVolumeShards']),
                     data['originalFilesCompleted'], data['originalSeeksConfirmed'], data['requestsWhileHeld'])
                    for name, data in current_public['cores'].items()]
    private = load('private-originals-19.json')
    private_rows = [(name, data['movingOpens'], data['opens'], data['confirmedSeeks'], data['opens'] * 2)
                    for name, data in private['cores'].items()]
    playback = load('playback-issues.json') if (ROOT / 'playback-issues.json').exists() else {'issues': []}
    playback_rows = [(i['id'], i['finding'], i['classification'], i['change'], i['validation'])
                     for i in playback['issues']]
    long_charts = ''.join('<details><summary>' + esc(p.stem) + '</summary>' +
                         embedded_image(p, str(p.relative_to(ROOT))) +
                         '<p><a href="long-curves/' + esc(p.stem) + '.svg">导出 SVG</a> · <a href="long-curves/' +
                         esc(p.stem) + '.csv">原始计数 CSV</a></p></details>' for p in sorted((ROOT / 'long-curves').glob('*.png')))
    discovery_panels = ''.join(embedded_image(p, p.stem + '：实际八张轮播海报中的四张，选择状态与截图前后核对。')
                               for p in sorted((ROOT / 'discovery-review-24').glob('*.png')))
    discovery_before = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116/discovery-ui-22/ui-verified-primary-light/ui/01-discover-top.png'
    discovery_after = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116/discovery-ui-24/ui-verified-primary-light/ui/01-discover-hero-3-attempt-1.png'
    release_record = '../releases/20261119-ycore-senplayer-1.9.6/verification.json'
    signed = (ROOT / release_record).is_file()
    release_card = (f'<a href="{release_record}">1.9.6（348）已签名</a>' if signed else '待签名')
    document = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Yfuse 云端功耗活动与界面审查报告</title>
<style>
:root{{color-scheme:light;--ink:#182536;--muted:#536375;--line:#dce4ec;--blue:#275ca5}}*{{box-sizing:border-box}}body{{margin:0;background:#f4f7fa;color:var(--ink);font:15px/1.75 system-ui,"Microsoft YaHei",sans-serif}}main{{max-width:1220px;margin:40px auto;padding:0 24px 60px}}h1{{font-size:30px;line-height:1.35}}h2{{font-size:21px;margin:38px 0 14px}}p{{max-width:1000px}}.meta,figcaption{{color:var(--muted);font-size:13px}}.notice{{border-left:4px solid #bd6a11;background:#fff6e9;padding:16px 20px}}.cards{{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin:24px 0}}.card{{background:white;padding:20px;border:1px solid var(--line);border-radius:12px}}.card strong{{display:block;font-size:26px;color:var(--blue)}}.table{{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:white}}table{{border-collapse:collapse;width:100%;font-size:13px}}th,td{{text-align:left;vertical-align:top;padding:12px 14px;border-bottom:1px solid var(--line)}}th{{background:#eaf0f6;white-space:nowrap}}td{{min-width:100px;max-width:380px;overflow-wrap:anywhere}}figure{{margin:18px 0;padding:16px;background:white;border:1px solid var(--line);border-radius:12px}}img{{max-width:100%;height:auto}}a{{color:var(--blue)}}code{{overflow-wrap:anywhere}}li{{margin:6px 0}}@media(max-width:700px){{.cards{{grid-template-columns:1fr}}main{{padding:0 14px}}h1{{font-size:25px}}}}@media print{{body{{background:white}}main{{margin:0;max-width:none}}.table{{overflow:visible}}h2{{break-after:avoid}}tr,figure{{break-inside:avoid}}}}
</style><main>
<p class="meta">云端 ARM64 Android · 记录更新时间 {esc(timestamp)}</p>
<h1>Yfuse 功耗活动与界面迭代报告</h1>
{candidate_progress_45()}
<div class="notice"><strong>当前为进行中的验证报告，尚未最终签收。</strong> 云端模拟器没有真实电池和功耗轨测量，本报告不能给出手机真实耗电百分比、mAh、mW或各模块能耗占比。曲线用于分析实际记录的 CPU、请求、唤醒、渲染等活动；缺失读数保留为空。</div>
<div class="cards"><div class="card">已完成非预检记录<strong>{completed_hours:.2f} 小时</strong><span class="meta">各实例实际记录时长累计，不代表单次续航</span></div><div class="card">当前迭代<strong>{esc(progress['iteration'])}</strong><span class="meta">产品、测量脚本和证据有效性分别复核</span></div><div class="card">本地安装包<strong>{release_card}</strong><span class="meta">私人原片起播和拖动仍待补测</span></div></div>
<p>本轮产品提交 <code>{esc(progress['candidateSourceCommit'])}</code>。{esc(progress['status'])}</p>
{current_playback_evidence()}\n{source_read_repairs()}\n{round34_evidence()}\n{round33_public_results()}\n{profile5_source_progress()}\n{playback_and_poster_recheck()}\n{sampled_playback_activity()}\n{candidate_checks()}\n{private_originals_28()}\n{private_repair_progress()}\n{recent_checks()}
<p><a href="iteration-26-verification.json">第 26 轮的片尾复现及早期修复</a> · <a href="ui-review-25/index.json">第 25 轮全页面截图与比对索引</a>。</p>
{current_activity_section(progress['fourHourVerification'])}
{model_components_section(progress['fourHourVerification'])}
{candidate_playback}
<h2>测量方法与边界</h2><ul><li>每30秒记录主应用进程 CPU；每约120秒记录 Batterystats、唤醒锁、任务、闹钟、定位、网络和帧统计。阶段切换和脚本操作的区间分别标注。</li><li>CPU 是单个模拟 CPU 核的占用百分比，不是耗电百分比；未运行的进程和缺失计数不会填为0。</li><li>播放流量及请求量只覆盖受控片源服务；其他网络活动另保留 UID 网络原始数据，不能把受控接口的0请求理解为整个应用没有联网。</li><li>电池电量为模拟值；设置80%仅用于满足调度条件。软件渲染器的帧耗时不能作为实体手机的帧率或续航承诺。gfxinfo记录应用UI帧，视频Surface的解码画面不包含在这个计数中。</li><li>ADB 采样可能唤醒模拟器，因此这不是无人干预的真实休眠测试。公共片源兼容性与私有 Emby 片源验收分开记录。</li></ul>
<h2>功耗异常与修复记录</h2>{table(['编号','级别','归类','原因 / 现象','修改','验证状态'],power_rows)}
{embedded_image(ROOT/'static-idle-paired.png','静止页面配对验证：内容已加载、关闭轮播、搜索已提交且输入框无焦点。CPU 和 UI 帧率均为活动指标，不是电池耗电比例。')}
<p><a href="static-idle-paired.csv">静止页面前后对照 CSV</a> · <a href="static-idle-paired.svg">对照图 SVG</a> · <a href="power-anomalies.csv">异常及其他活动归类 CSV</a></p>
{embedded_image(ROOT/'short-paired-overview.png','短时前后配对：后台诊断采样次数及主进程 CPU。该变化不换算为电池节省比例。')}
{embedded_image(ROOT/'carousel-paired.png','轮播开启的媒体库前后配对：每版约16分钟，静置阶段排除首60秒；手动滑动保留，修复后停止静置刷新。')}
<p><a href="carousel-paired.csv">轮播前后阶段数据 CSV</a> · <a href="carousel-paired.svg">轮播对照图 SVG</a> · <a href="carousel-paired-verification.json">完整配对验证记录</a></p>
<h2>长时间记录与模块曲线</h2>{recordings_table}{long_charts}
<p>旧版13阶段记录未证明断网步骤实际收到HTTP503，且拖动目标重复；相应阶段不算作网络异常或双向拖动验收。新版协议先确认本机测试片源、验证新增503响应，再交替拖动18%与62%位置。模拟电量及测试条件不同的记录不直接用来计算节电比例。</p>
<p><a href="power-phase-results.csv">下载各阶段数据 CSV</a> · <a href="power-recording-index.json">下载记录及源码索引</a> · <a href="short-paired-results.csv">下载短时配对数据</a> · <a href="long-paired-results.csv">下载四小时配对数据</a></p>
<h2>界面问题及修复</h2>{table(['编号','级别','问题','修改 / 结论','验证状态'],ui_rows)}
<p><a href="ui-fix-register.csv">下载完整问题登记表</a> · <a href="ui-issues.json">下载截图范围、证据限制及详细记录</a></p>
<details><summary>UI-028：发现页同一明亮海报的修复前后及完整16张复核</summary>
<p>1. 从观看进入发现页，等待内容加载并关闭一次性提示。2. 逐个选择八张轮播海报，确认截图前后的选中状态。3. 在浅色普通字体及深色130%字体下复核标题、年份、简介和按钮。此范围通过；不据此宣称所有无障碍要求合规。</p>
<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px">{embedded_image(discovery_before,'修复前：白色文字落在明亮背景上。')}{embedded_image(discovery_after,'修复后：局部文字渐变保护，同一海报和字号。')}</div>
{discovery_panels}<p><a href="discovery-review-24/review.json">16张截图路径、哈希、选择状态与复核记录</a>。</p></details>
<h2>迭代过程</h2>{table(['轮次','类型','修改 / 发现','验证状态'],iteration_rows)}
<h2>已完成的上一版播放验证</h2>{table(['内核','已核对批量拖动','已核对分片','公共原片文件','原片拖动确认','悬停时新请求'],current_rows)}
<p>该轮播放源码 <code>{esc(current_public['source'])}</code>，27,520次批量拖动全部逐项核对。每内核88个公共原片文件已完成；缩略图悬停的7个阶段均通过，缓存外不显示。这些数据不计为当前候选通过。<a href="final-public-partial-19.json">该轮完整数据</a>。</p>
<h2>同一批私人原片的早期基线</h2>{table(['内核','持续画面确认','打开总数','拖动确认','拖动总数'],private_rows)}
<p>上表来自旧版本的 32 部电影和 28 集剧集。电影为 619 MB–195.74 GB，剧集为 105 MB–40.19 GB。在第 24 轮时，60 个文件均按大小、容器和时长唯一匹配。当前剧库可重现其中 59 个，另一集的替换记录见本轮原片表。<a href="private-originals-19-by-file.json">双内核逐片结果</a> · <a href="private-catalog-24.json">本次目录核对</a>。</p>
<p>旧版 YCore 的电影起播 10/32，剧集起播 15/28。35 个未确认起播的样本均有 Android 端 HTTP 206 字节范围响应。SenPlayer 的电影起播 32/32、拖动 58/64，剧集起播 28/28、拖动 56/56。<a href="private-format-coverage-19.csv">按电影、剧集、容器和视频编码查看大小范围与结果</a>。</p>
<h2>历史批量验证</h2>{table(['内核','历史已确认拖动','已核对分片'],seek_rows)}
<p>早期两个 APK 共发出 11,485 次拖动，其中 9,514 次确认了目标处的连续画面。其余 1,971 次中，1,745 次只有 6.5 秒观察窗口，未留下逐帧轨迹。另一批 226 次保留了最后一帧，其中 153 次在目标附近、48 次偏早、19 次偏晚、6 次读不到画面。按原随机种子回放，确认部分手势传给内核的目标错误。后续长片专项另发现时间文字会改变轨道宽度。缺少逐帧证据的部分仍无法逐次归因。<a href="../legacy-engine-20261113/dual-engine-cloud/report.md">历史对照与修复</a> · <a href="../legacy-engine-20261113/volume-summary.json">原始计数</a>。</p>
<p>历史32分片来源提交 <code>{esc(partial_volume.get('product', partial_volume.get('source')))}</code>，单独保留。批量拖动使用公共作品的制备片段；原片格式、尺寸和播放情况另行核对，不能相互替代。</p>
<h2>播放问题与当前处理</h2>{table(['编号','问题','归类','处理','验证状态'],playback_rows)}
<h2>真实账号与跨设备验证</h2><p>{esc(progress.get('realAccountIntegration', {}).get('latestStatus'))}</p>
<p>本轮清理状态：{esc(progress.get('realAccountIntegration', {}).get('cleanupPending'))} 后端部署凭证：<a href="backend-timestamp-review/deployment-receipt.json">创建时间修复部署及健康检查</a>。</p>
<p><a href="latest-public-originals.json">原片逐内核汇总</a> · <a href="latest-public-volume-partial.json">已核对拖动分组</a> · <a href="audio-long-verification.json">一小时后台专项核对</a> · <a href="playback-issues.json">播放问题完整记录</a></p>
<h2>待验证范围</h2><ul><li>{esc(progress['finalValidation'])} 逐片记录保留了未起播、未发出拖动和拖动后画面未确认三种情况。</li><li>真实账号、遥控、接力和电视方向键接收已验证。外部 Trakt 授权和实体投屏接收器尚未取得云端实测证据。</li><li>页面截图范围、受保护画面和设备能力限制见 UI 问题登记表。</li></ul>
<p class="meta">详细机器可读进度：<a href="progress.json">progress.json</a>。页面不使用外部脚本、字体或图片服务。</p>
</main></html>'''
    (ROOT / 'report.html').write_text(document, encoding='utf-8')
    print(json.dumps({'report': str(ROOT / 'report.html'), 'recordings': len(records), 'completedNonPilotHours': round(completed_hours, 3)}, ensure_ascii=True))


if __name__ == '__main__':
    main()
