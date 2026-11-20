"""Record immutable source scopes and the measured long-movie geometry defect."""
from pathlib import Path
import json
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent
NOW = datetime.now(timezone.utc).isoformat()
SOURCE = 'fe814ec63fb1c035ff9fb123146e3ed3700a2b16'

def read(name):
    return json.loads((ROOT / name).read_text(encoding='utf-8-sig'))

def save(name, data):
    (ROOT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

p = read('progress.json')
p.update(updatedUtc=NOW, iteration=24, productCommit=SOURCE, candidateSourceCommit=SOURCE,
         productRuntimeCommit=SOURCE, qualificationBuildRun=44067422996,
         status='长片拖动几何变化已复现；左右时间读数的完整修复正在云端资格验证，原片验收未关闭',
         signingAllowed=False, lastQualifiedProductCommit='b58ddf8060a2ccfae2d214fa3e6c544849ba6557')
p['activeRuns'].update(qualification=44067422996, tvQualification=44067423124,
                       discoveryUi=44067765813, formatDiagnostics=44064491416,
                       massSeek=None, publicOriginals=None, privateOriginals=None, fourHourActivity=None)
p['activeStudies'] = [dict(run=44045030245, phaseCount=13, minutesPerCore=240,
                          source='141ed26051de24641214d34828324d71fabd115c',
                          status='in progress; earlier-source baseline, not the current candidate')]
p['candidateQualification'] = dict(run=44067422996, source=SOURCE, status='in_progress', tvRun=44067423124)
p['latestUiQualification'] = dict(source='b58ddf8060a2ccfae2d214fa3e6c544849ba6557', run=44062488481,
    hostTests=5735, hostSuites=990, hostFailures=0, hostErrors=0, hostSkipped=0,
    repeatedTests=300, repeatFailures=0, nativeCompleted=7, nativeFailed=0, nativeSkipped=0,
    rawEvidenceVerified=True)
p['supersededCandidateRuns'] = [
    dict(source='b58ddf8060a2ccfae2d214fa3e6c544849ba6557',
         runs=[44062748056,44062748052,44062748289,44062748286], cancelRun=44064487446,
         reason='长片几何缺陷，未启动私人中继，取消不计通过'),
    dict(source='4c41e85a551618131421abd2027422f10dc45f54',
         runs=[44065974990,44065974827,44065975329,44065975145], cancelRun=44067422956,
         reason='预计结束时间仍参与右侧测量，完整修复后再启动；未启动私人中继'),
]
p['requiredCoverageNotYetComplete'] = [
    '最新fe814修复需通过5735主机项、300取消重复、旧布局反例及8项真实设备契约。',
    '0127历史源码的27520次批量拖动、每内核88公共原片已逐项核对；不计为当前源码通过。',
    '60私人原片旧版YCore持续输出25/60、拖动41/120；SenPlayer持续输出60/60、拖动114/120。完整修复后复测。',
    '44064491416旧布局双内核各3/3持续打开、8/12拖动确认；两类长片63%落点偏移，60秒4K各4/4通过。',
    '141旧源码四小时13阶段仍在记录；当前候选的四小时记录尚未启动。',
    '333视口与补采已复核；发现页UI-028已修改，44067765813复拍8张轮播图×双主题。',
    '后端a17已部署；44050291685账号、三次刷新及双端接力通过并完成清理。',
    '全部必要门禁闭环后签名；云端活动指标不等于实体电池能耗。',
]
for r in p['iterations']:
    if r.get('round') == 22:
        r['validationZh'] = 'aaf恢复修复5735主机项、300次取消边界重复和7项原生契约全过；私人失败仍需原片验证。'
    if r.get('round') == 23:
        r['source'] = 'b58ddf8060a2ccfae2d214fa3e6c544849ba6557'
        r['validationZh'] = 'b58资格5735+300+7全过；首次全轮播补采因选中圆点无点击动作而中断，保持失败证据，修复采集脚本后重拍。'
p['iterations'] = [r for r in p['iterations'] if r.get('round') != 24]
p['iterations'].append(dict(round=24, source=SOURCE, typeZh='长片拖动几何稳定性',
    changesZh='固定两端完整时长读数宽度；预计结束时间独立叠放，避免文案变化挤压进度条。真实设备测试同时验证普通与130%字体，旧布局必须失败。',
    validationZh='旧源码双内核实测拖动时右端边界改变6至14px；原片问题不能据此直接关闭。完整修复资格与同样片源实播待返回。'))
p['finalValidation'] = '未最终验收：真实账号已通过并清理；长片几何修复正在验证，最新源码原片、大量拖动及四小时活动复测未完成。'
save('progress.json', p)

s = read('source-scope.json')
s.update(updatedUtc=NOW, latestSource=SOURCE, latestActivityRun=None)
s['seekGeometryDelta'] = dict(fromSource='b58ddf8060a2ccfae2d214fa3e6c544849ba6557',
    intermediateSource='4c41e85a551618131421abd2027422f10dc45f54', toSource=SOURCE,
    beforeDiagnosticRun=44064491416, qualificationRun=44067422996,
    files=['composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerChromeRefined.kt'],
    limit='新布局改变触摸目标与预览映射，必须重跑真实拖动；原片6次失败尚不能直接关闭。')
s['discoveryContrastDelta']['verificationRun'] = 44067765813
s['discoveryContrastDelta']['verificationSource'] = 'b58ddf8060a2ccfae2d214fa3e6c544849ba6557'
s['discoveryContrastDelta']['scope'] = '发现页、Hero及媒体库caption生产代码与最新候选相同；此处只验证海报对比，不替代最新播放器检查。'
save('source-scope.json', s)

u = read('ui-issues.json')
u['updatedUtc'] = NOW
for i in u['issues']:
    if i['id'] == 'UI-028':
        i.update(verificationRun=44067765813, previousCaptureRun=44062748117,
                 statusZh='资格通过；先前全轮播采集仅首张成功，已修复脚本，正在重新采集并核对8张×双主题')
save('ui-issues.json', u)

b = read('playback-issues.json')
b['updatedUtc'] = NOW
for i in b['issues']:
    if i['id'] == 'PLAY-004':
        i.update(change='aaf软件恢复移除重复探测，实际解封装后保留HDR/受保护内容校验；5735+300+7资格通过。其余超时、吞吐及解码能力继续按文件分组。',
                 validation='恢复代码已通过资格，60私人原片尚未重测，35次启动失败未关闭。')
    if i['id'] == 'PLAY-005':
        i.update(classification='长片拖动期间两侧读数改变进度条测量宽度；原片偏移关联待同文件验证',
                 change='实际输入对照显示旧布局右端变化6至14px；固定读数宽度并让预计结束时间不参与测量。保持原落点容差、正常首次操作与诊断重试分开计数。',
                 validation='44064491416两内核同样长片均复现；fe814完整修复资格与原片回归待完成。',
                 fixSource=SOURCE, qualificationRun=44067422996, diagnosticRun=44064491416)
save('playback-issues.json', b)
print(json.dumps({'iteration':24,'source':SOURCE,'qualificationRun':44067422996,'signingAllowed':False}))
