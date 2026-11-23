import sys,time,re,json,subprocess,pathlib
sys.path.insert(0,'qa-functional')
import features as F
from ext_run import adb,screen,tap
out=pathlib.Path('menu-results');out.mkdir(exist_ok=True);records={}
kind=sys.argv[1];bad=re.compile(r'(?i)\b(?:mpv|mdk|exo(?:player)?)\b|libmpv|libmdk|ExoPlayer')
def capture(name):
    t=F.snapshot();words=F.page_info(t);screen().save(out/(name+'.png'))
    records[name]={'labels':words,'forbidden':[w for w in words if bad.search(w)]}
    (out/'labels.json').write_text(json.dumps(records,ensure_ascii=False,indent=2));print('VIEW',name,json.dumps(words,ensure_ascii=False),flush=True)
    return t,words
def click(keys,swipes=4):
    n=F.grab(keys,swipes);F.tap(n)
def verify(words,need):
    assert not any(bad.search(w) for w in words),'legacy name on current screen'
    for key in need:assert any(key in w for w in words),'missing evidence: '+key
def tv_find(keys,attempts=8):
    for i in range(attempts):
        t=F.snapshot();n=F.find(t,keys)
        if n is not None:return n
        hit=next((n for n in F.nodes(t) if any(k in x for x in F.labels(n) for k in keys)),None)
        if hit is not None:return hit
        adb('shell','input','keyevent','20' if i%4!=3 else '21');time.sleep(.35)
    raise LookupError(str(keys))
try:
    F.pkg='com.yfuse'
    if kind=='phone':
        F.launch();F.root('我的');click(['播放设置','播放与字幕'],7);time.sleep(.6);capture('phone-playback-settings');click(['高级播放设置','高级播放'],10)
        F.wait('内核与解码',15);click(['播放内核'],4)
        F.wait(['高级内核选择','自动选择（推荐）'],12)
        _,words=capture('phone-settings-engine-picker')
        verify(words,['自动选择','YCore'])
        adb('shell','input','keyevent',4)
        import http.server,threading
        class Local(http.server.SimpleHTTPRequestHandler):
            def __init__(self,*a,**k):super().__init__(*a,directory='qa-media',**k)
            def log_message(self,*a):pass
        server=http.server.ThreadingHTTPServer(('127.0.0.1',18479),Local);threading.Thread(target=server.serve_forever,daemon=True).start()
        adb('reverse','tcp:18479','tcp:18479')
        adb('shell','am start -a android.intent.action.VIEW -n com.yfuse/com.yfuse.feature.player.ExternalPlaybackActivity -t video/mp4 -d http://127.0.0.1:18479/ProbeA.mp4')
        for i in range(22):
            tree=F.snapshot();node=F.find(tree,['打开','确认打开','知道了','Got it','Open'])
            if node is not None:F.tap(node);time.sleep(.65);continue
            more=F.find(tree,'更多')
            if more is not None:F.tap(more);break
            im=screen();tap(im.width*.52,im.height*.36);time.sleep(.6)
        F.wait('播放内核',20);click('播放内核');time.sleep(1.2)
        _,words=capture('phone-playing-engine-menu');verify(words,['YCore'])
    else:
        adb('shell','monkey','-p','com.yfuse','-c','android.intent.category.LEANBACK_LAUNCHER','1')
        time.sleep(7);capture('tv-initial')
        F.tap(tv_find(['我的与设置','设置中心','设置']));time.sleep(1)
        search=tv_find(['搜索设置'],8);F.tap(search);adb('shell','input','text','ycore');time.sleep(.7)
        if 'true' in adb('shell','dumpsys','input_method').split('mInputShown=')[-1][:5]:adb('shell','input','keyevent','4')
        capture('tv-search');F.tap(tv_find(['高级播放','内核、解码与设备输出'],12));time.sleep(.8)
        capture('tv-advanced');seen=[]
        for i in range(4):
            t,w=capture('tv-choice-'+str(i));seen+=w
            F.tap(tv_find(['高级内核选择']));time.sleep(.4)
        verify(seen,['自动选择','YCore'])
except Exception as e:
    capture(kind+'-last');print('NOT VERIFIED',type(e).__name__,str(e),flush=True);raise
