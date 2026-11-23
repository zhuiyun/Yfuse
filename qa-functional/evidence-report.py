from pathlib import Path
import json,html,base64,collections,io,sys
from PIL import Image
P=Path('raw'); OUT=Path('report');OUT.mkdir(exist_ok=True)
def find(k,f):return next(iter((P/k).rglob(f)),None)
def data(k,f):
 p=find(k,f);return json.loads(p.read_text()) if p else None
def tag(s):return html.escape(str(s))
def table(head,rows):
 return '<div class="scroll"><table><thead><tr>'+''.join('<th>'+tag(i)+'</th>' for i in head)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+tag(x)+'</td>' for x in row)+'</tr>' for row in rows)+'</tbody></table></div>'
def section(t,s):return '<section><h2>'+tag(t)+'</h2>'+s+'</section>'
def pic(key,file,caption):
 p=find(key,file)
 if not p:return ''
 im=Image.open(p).convert('RGB');im.thumbnail((880,480));b=io.BytesIO();im.save(b,'WEBP',quality=82)
 uri='data:image/webp;base64,'+base64.b64encode(b.getvalue()).decode()
 return '<figure><img src="'+uri+'" alt="'+tag(caption)+'"><figcaption>'+tag(caption)+'</figcaption></figure>'
def sumseq(key):
 ret={}
 for f in sorted((P/key).rglob('results.json')):
  rs=json.loads(f.read_text())['results']
  ret[f.parent.name]={'ok':sum(x['confirmed'] for x in rs),'total':len(rs),'shots':[{'target':x['target'],'ok':x['confirmed'],'early':x.get('firstSampleSec'),'late':x.get('lateFrame'),'elapsedSwipe':x.get('issuedSeconds')} for x in rs]}
 return ret
res={k:sumseq(k) for k in ['all','hls','standard','soak']}
res['minute']=data('soak','uninterrupted-minute.json') or []
res['picture']=data('standard','picture.json') or []
res['upgrades']=data('upgrade','cases.json') or []
res['subtitle']=data('subtitle','subtitle-results.json') or {}
res['shared']=dict(collections.Counter(x['result'] for x in data('shared','cases.json') or []))
(OUT/'measurements.json').write_text(json.dumps(res,ensure_ascii=False,indent=2))
summary={}
for k in ('all','hls','standard','soak'):
 summary[k]={name:[v['ok'],v['total']] for name,v in res[k].items()}
summary.update(minute=[sum(x['ok'] for x in res['minute']),len(res['minute'])],picture=[sum(x['confirmed'] for x in res['picture']),len(res['picture'])],upgrade=res['upgrades'],shared=res['shared'],subtitle=res['subtitle'].get('passed'))
print('FINAL_MEASUREMENTS '+json.dumps(summary,ensure_ascii=True),flush=True)
style='body{font:16px/1.6 system-ui,sans-serif;color:#1c3440;background:#f0f5f6;margin:0 auto;padding:22px;max-width:1080px}header,section{background:white;padding:20px 25px;border-radius:12px;margin:16px 0}header{background:#113747;color:white}h1,h2{line-height:1.3}h2{font-size:21px}table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:8px;border-bottom:1px solid #d8e4e5;vertical-align:top}th{background:#e7f1f1}.scroll{overflow-x:auto}.pics{display:grid;grid-template-columns:1fr 1fr;gap:12px}figure{margin:8px 0}img{width:100%}figcaption{font-size:13px}a{color:#00667a}@media(max-width:680px){body{padding:7px}section,header{padding:14px}.pics{grid-template-columns:1fr}}'
s='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Yfuse Android 模拟器回归</title><style>'+style+'</style><header><small>2026-11-12 · Yfuse 1.9.4（346）</small><h1>Android 模拟器功能与播放回归</h1><p>Android 15、17 模拟器，Android 15 连接真实 Emby Server 4.10.2.0。逐帧验证所选位置及后续走时。最终应用提交 1bd86af3。</p></header>'
s+=section('需要优化的地方与修复',table(['发现','处理'],[
['进度条快速松手落点错误，横划画面时少算最后一段位移','进度条与画面手势在提交跳转前处理最后触点，保留取消手势及章节吸附。原始测试曾将 108 秒拖到 64.625 秒。'],
['连续拖动 HLS 报错，或先到目标再漂移','在当前 HLS 点播清单内沿用解封装器和分片缓存，避免每次重建。DASH 跨时段继续独立选路。基线曾在 2.625 秒停住，也曾从目标 1:42 又跳到 2:07。'],
['播放异常可能让下一部影片也打不开','等待占用解码器的阻塞读取完成最终释放，重连保留已选增强路由，移除对旧媒体的重复跳转。'],
['失败后选择替代内核只显示暂停','从错误页手动换内核时恢复播放意图。旧内核故障后经手动选择，确认 YCore 从 0.500 秒播到 1.625 秒。'],
['x86 模拟器因支持翻译 ARM 应用而错误显示 SCore','按设备首选 64 位 ABI 及进程位数判断能否加载隔离的 ARM64 运行库。完整升级结果在下表。'],
['深色界面旋转后状态栏时间难辨认','配置变化后由当前可见页面重新应用图标前景色。两系统共 12 个对照样本。'],
['大型首页组件备份恢复时耗尽内存','按最终写出的 UTF-8 字节预算限制 JSON，超限先退出，原数据完整。'],
['2 万条 SAMI 字幕可能导致进程退出','保留标签必要位置和属性，消除对整份输入的重复持有及重复文本清理。测试末条时间戳与中途取消。'],
['IPTV 与 Wi-Fi 导入窗口正文折叠','移除外层重复滚动，让内容取得可用高度。导入两频道、按名筛选、Wi-Fi 配对传送视频均已执行。']]))
labels={'all':'两部直放加 HLS，每组连拖三次','hls':'重新启动的模拟器，HLS 每组连拖三次','standard':'原生滑动指令，逐次拖动','soak':'HLS 固定随机种子，24 组连续三次拖动'}
rows=[]
for k,label in labels.items():
 for clip,v in res[k].items():rows.append([label,clip,str(v['ok'])+'/'+str(v['total']), '最终画面在最后目标附近，继续走时；三连拖另在 2.5 秒后复查。'])
rows.append(['直接划画面','ProbeA',str(summary['picture'][0])+'/'+str(summary['picture'][1]),'包含最后位移只在抬手时上报。'])
rows.append(['压力测试后连续播放一分钟','ProbeA HLS',str(summary['minute'][0])+'/'+str(summary['minute'][1]),'约每 5 秒取一帧，核对片内时间码与经过时间。'])
s+=section('最终构建实测',table(['方式','媒体','通过/执行','判定'],rows))
s+=section('拖动前后的真实画面','<div class="pics">'+pic('baseline','01-later.png','旧版独立 HLS 复现，已到过目标 1:42 又跳到 2:07.125。')+pic('hls','01-later.png','修复后同一 HLS 和目标，延后复查仍在正确时间段。')+'</div>')
s+=section('已有功能覆盖',table(['范围','实际验证'],[
['启动和界面','Android 15、17 各 22 项，含安装、冷启动、导航返回、前后台、断网、大字号、横屏及平板宽度。'],
['真实媒体库','实测正确/错误登录、分页、搜索、详情打开和返回、收藏及撤销、已看状态写后再读。'],
['离线下载','App 下载完成的文件和来源 SHA-256 一致，从本地文件解出第 65 秒画面后删除。媒体业务操作 10/10。'],
['导入和外部来源','IPTV 导入和筛选、Wi-Fi 配对上传、WebDAV 添加和目录列表 5/5。'],
['异常状态','连续两轮无效媒体后在同一进程打开正常影片，验证真实视频走时。验证 Android 媒体键暂停与恢复。'],
['字幕与音频','从影片详情选择 Emby 的外置英文 SRT，在 7.75 秒截图识别到 Subtitle fixture。关闭后 14.875、15.875、16.75 秒三次均消失。样片 AAC 在服务器另行抽样。'],
['设置和观看页','默认倍速 1.5x 跨重启保存、恢复 1x。检查观影统计、日历、片单、发现、家庭资料等入口；第三方登录与实际联机另计。'],
['共享逻辑','Android 设备业务测试 '+str(summary['shared'].get('passed',0))+' 项通过。另有 16 类共 252 项注入宿主机/虚拟调度器的测试由项目标准 Android HostTest 执行。'],
['原拖动回归','包含本地、直放、HLS 的常规 22/22，含快速松手及移动事件合并的另一轮 22/22。设备首轮外部拖动基线 11/16。']]))
s+=section('升级、内核以及家庭资料',table(['操作','结果','设备观察'],[[v['name'],v['result'],json.dumps(v.get('detail'),ensure_ascii=False)] for v in res['upgrades']]))
s+=section('界面与字幕截图','<div class="pics">'+pic('subtitle','subtitle-on.png','开启英文外挂字幕后的视频画面。')+pic('subtitle','subtitle-off.png','同一条字幕仍应出现的时段手动关闭。')+pic('priorfeatures','iptv-import-real-m3u.png','修复后的 IPTV 导入。')+pic('library','05-download-entry.png','影片详情中的下载确认页。')+'</div>')
s+=section('仍需对应设备或服务验收','<p>OPPO 原设备和原片、真实 HDR / Dolby Vision 显示、ARM64 真机上的 SCore 授权解码、Cast 电视接收端、第三方账号、多端同步和公网弱网仍需对应环境。发现页在隔离测试环境提示推荐服务认证失败。测试覆盖以上执行过的场景。</p>')
s+=section('代码和原始运行记录','<p><a href="https://github.com/zhuiyun/Yfuse/pull/279">修复 PR #279</a></p><ul>'+''.join('<li><a href="https://github.com/zhuiyun/Yfuse/actions/runs/'+str(i)+'">'+tag(t)+'</a></li>' for i,t in [(43271135331,'最终构建应用功能复验'),(43272214769,'72 次 HLS 压力拖动及连续播放'),(43269615384,'外挂字幕开启与关闭'),(43269280583,'Android 设备共享逻辑'),(43271195229,'Android 工程与设备测试'),(43271195885,'TV 工程'),(43266819964,'原始 HLS 后续偏移'),(43262539122,'IPTV 与 Wi-Fi 修复后')])+'</ul>')+'</html>'
(OUT/'Yfuse-Android-Regression-20261112.html').write_text(s)
