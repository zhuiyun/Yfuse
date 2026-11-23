"""Reconcile verified cloud evidence; leave outstanding gates explicitly open."""
import datetime
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NOW = datetime.datetime.now(datetime.timezone.utc).isoformat()
SOURCE = '0127f268d4fb00b4a83c08640b0803a76cfac866'


def read(name):
    return json.loads((ROOT / name).read_text(encoding='utf-8'))


def write(name, value):
    value['updatedUtc'] = NOW
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


p = read('progress.json')
p.update(iteration=19, productCommit=SOURCE, candidateSourceCommit=SOURCE,
         productRuntimeCommit=SOURCE, signingAllowed=False,
         qualificationBuildRun=44034015111, subtitleFunctionalRun=44032901251,
         finalValidation='未完成最终验收：字幕70项实播检查和真实账号27项记录通过；最新源码的大量拖动、原片和四小时活动复测仍在进行。',
         preparedNotDispatched=[], localUncommittedChanges=[],
         privateRelayPending='44036346376两台云端模拟器已进入原片实播；60部片源开头/中段/末尾共180次范围读取206。临时转发最长4小时、32GiB，需在结束时清理。')
p['requiredCoverageNotYetComplete'] = [
    '0127最终源码：双内核27,520次拖动、公开原片及私人60部原片实播尚未完成',
    '0127两内核各四小时13阶段活动复测尚未完成；轮播16分钟配对已通过',
    '已部署后端需真实账号连续三次刷新检查；最新电视包需D-pad接受接力及完整播放验证',
    '最新全部门禁闭环后，才可签名打包并验证最终APK',
    '云端没有真实电池mAh或物理模块能耗；数据仅代表活动代理指标，不能保证绝对稳定',
]
p['candidateQualification'] = {
    'run': 44034015111, 'source': SOURCE, 'status': 'passed',
    'hostTests': 5732, 'hostFailures': 0, 'hostErrors': 0, 'hostSkipped': 0,
    'repeatedProbeTests': 300, 'bridgeAssertions': 180,
    'tvRun': 44034015185, 'tvBuildCommit': '5276e310fa575a1244bb2b611d4b58020192e306',
    'tvStatus': 'passed', 'tvSourceEquivalence': 'Only qa-integrated-av-repair.yml differs from product source',
}
p.setdefault('historicalRuns', {}).update(p.get('activeRuns', {}))
p['activeRuns'] = {'massSeek': 44036346424, 'publicOriginals': 44036346367,
                   'privateOriginals': 44036346376, 'fourHourActivity': 44036346513,
                   'accountRefreshAndDpad': 44039164869}
p['activeStudies'] = [{'run': 44036346513, 'phaseCount': 13, 'minutesPerCore': 240,
                       'source': SOURCE, 'status': 'in progress; final result pending'}]
p['realAccountIntegration'].update(
    latestRun=44039164869, status='in_progress', priorRun=44034933885,
    priorPassedCases=27, priorCleanup={'automatic': True, 'failedSteps': 0, 'historySynced': True},
    latestStatus='44034933885全流程通过：手机/电视真实登录、遥控授权和撤销、服务器传递、跨内核接力及来源暂停。新轮验证三次刷新、D-pad及最新源码。',
    cleanupPending='无已知遗留记录；44034933885唯一影片已显式结束撤销窗口并同步删除，新轮登录再次验证历史删除已持久化。')
p['backendQualification'].update(deployed=True, deploymentReceipt='backend-review/deployment-retry2-receipt.json',
    maintenanceSeconds=9.63, localHttpUnauthenticated=426, publicHttpsUnauthenticated=401,
    realLoginVerifiedRun=44034933885, refreshIdentityVerificationPending=True)
p['iterations'] = [i for i in p['iterations'] if i.get('round') != 19] + [{
    'round': 19, 'source': SOURCE,
    'changesZh': '让原生解除Surface绑定在Android销毁前完成，超时有界退出；轮播展示一轮后停止自动计时；部署稳定会话编号后端；修正云测撤销提示和接力期望进度。',
    'validationZh': '字幕短/长片段各35项通过；真实账号27条通过并清理；5732主机项及300次取消边界重复通过。轮播静置CPU中位数45.646%降至0.129%，手动交互后44.325%降至0.128%。最新大规模/四小时复测进行中。',
    'limits': 'CPU占一个云端虚拟核心的比例；不是电量节省比例。历史失败保留，不等于所有片源已通过。',
}]
write('progress.json', p)

play = read('playback-issues.json')
for item in play['issues']:
    if item['id'] == 'PLAY-002':
        item.update(status='verified in short and long subtitle functional cases', functionalRun=44032901251,
                    validation='c301源码：2秒及598秒字幕各35项通过，覆盖开关、重开、主副字幕、样式、拖动和前后台返回；无字幕路径亦通过。')
    if item['id'] == 'PLAY-003':
        item.update(status='verified scoped repair; broad candidate regression in progress',
            change='Surface销毁回调返回前有界等待原生detach完成（750ms）；超时原子退休原生会话并保留恢复信息，避免向失效Surface重绑定。原始许可和资产未变。',
            source='c301b0adbba71c6f6b1e58c4951c28658645e314',
            classification='原生Surface生命周期：异步detach晚于Android销毁Surface；长字幕状态不能靠重建会话完整恢复',
            validation='先于Surface失效detach的原始内核对照通过；44032901251短/长字幕各35项全部通过，实拍确认598秒字幕后台返回文字可见。保持原-6失败记录。',
            qualificationRun=44032786577, functionalRun=44032901251,
            diagnosticRuns=[44029378273, 44030867033, 44031597312])
write('playback-issues.json', play)

power = read('power-issues.json')
for item in power['issues']:
    if item['id'] == 'PWR-006':
        state = 'verified in source-specific four-hour study; final candidate rerun pending'
        item.update(status=state, statusZhFor=state,
            statusZh='30d两内核各475次采样/13阶段/约4小时完成；后台和熄屏CPU中位数0、受控媒体无新增字节。0127最终候选仍复测中。',
            longStudyRun=43996559269, evidence='timers-long-18')
power['issues'] = [i for i in power['issues'] if i['id'] != 'PWR-007'] + [{
    'id': 'PWR-007', 'severity': 'P2', 'category': '持续轮播与渲染刷新',
    'cause': '媒体库自动轮播在用户静置时持续切换；30d四小时记录的15分钟媒体库阶段CPU中位数约45%。',
    'change': '可见页面只自动展示一轮剩余项目；交互后可开始新一轮；隐藏/停止页面取消时钟，保留手动滑动。',
    'commit': SOURCE, 'status': 'paired measurement verified; final long study pending',
    'statusZhFor': 'paired measurement verified; final long study pending',
    'statusZh': '每版约16分钟配对完成：静置CPU45.646%→0.129%，交互后静置44.325%→0.128%；UI帧率2.810/2.879→0，手动滑动通过。最终四小时复测进行中。',
    'verificationRun': 44034504655, 'evidence': 'carousel-paired-verification.json',
    'limits': ['一个云端虚拟CPU核心的使用率，不代表真实功耗百分比', '每个静置阶段5分钟，排除初始60秒；最终长测尚未完成'],
}]
write('power-issues.json', power)

ui = read('ui-issues.json')
for item in ui['issues']:
    if item['id'] in ('UI-025', 'UI-026'):
        item['fix'] = item['change']
        item['validationRun'] = 44034933885
    if item['id'] == 'UI-025':
        item.update(status='actual focusable TV dialog and complete handoff verified; D-pad accept pending',
                    validation='实拍电视确认对话框可访问，接收后SenPlayer画面/进度推进且手机暂停；接收按钮D-pad在新一轮单独验证。',
                    afterEvidence=['private-account/44034933885-1/results/tv-handoff-offer.png'])
    if item['id'] == 'UI-026':
        item.update(status='verified on actual landscape sync and history pages',
                    validation='横屏同步页面实拍右侧系统导航栏之外保留内容间距；历史清理、同步按钮与导航均可操作。',
                    afterEvidence=['private-account/44034933885-1/results/test-history-synchronized-44034933885.png'])
for item in ui['measurementIssues']:
    if item['id'] == 'QA-028':
        item.update(status='verified in complete account integration', verificationRun=44034933885)
write('ui-issues.json', ui)

scope = read('source-scope.json')
scope.update(latestSource=SOURCE, longStudySource='30d6fd96b7defa4d1852e6e83c53a99131847a64',
    longStudyRun=43996559269, latestPlaybackEngineVerifiedSource='c301b0adbba71c6f6b1e58c4951c28658645e314',
    limit='All historical evidence remains source-specific. 0127 includes native lifecycle and carousel changes; its mass seek, original media and long activity runs remain pending.')
scope['subtitleSurfaceDelta'].update(source='c301b0adbba71c6f6b1e58c4951c28658645e314',
    functionalRun=44032901251, status='short and long subtitle native functional cases passed')
scope['carouselDelta'] = {'source': SOURCE, 'pairedRun': 44034504655, 'finalLongRun': 44036346513}
write('source-scope.json', scope)
print('Iteration 19 reconciled; final signing gate remains closed.')
