import json, os, pathlib, re, subprocess, sys, xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

def shell(*args):
    return subprocess.run(['adb', *args], capture_output=True, text=True, timeout=18).stdout.strip()

def guard():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('cloud runner required')
    abi=shell('shell','getprop','ro.product.cpu.abilist64')
    if abi.split(',')[0] != 'arm64-v8a':
        raise ValueError('a native ARM64 Android guest is required')

def configure(engine):
    guard()
    if engine not in ('YCore','SenPlayer'): raise ValueError('engine')
    shell('shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-p','com.yfuse')
    import time
    time.sleep(3)
    shell('shell','am','force-stop','com.yfuse')
    directory='/data/user/0/com.yfuse/shared_prefs'
    dest=directory+'/yfuse.xml'
    uid=shell('shell','stat','-c','%u','/data/user/0/com.yfuse')
    if not uid.isdecimal(): raise RuntimeError('cloud app uid unavailable')
    old=shell('shell','cat',dest)
    root=ET.fromstring(old) if old.startswith('<?xml') or old.startswith('<map') else ET.Element('map')
    for key,value in {'player.engine':engine,'player.ycore.engineSelection':'Lock'+engine}.items():
        node=root.find("./*[@name='"+key+"']")
        if node is None: node=ET.SubElement(root,'string',{'name':key})
        node.tag='string';node.text=value
    local=pathlib.Path('cloud-test-preferences.xml');local.write_bytes(ET.tostring(root,encoding='utf-8',xml_declaration=True))
    subprocess.run(['adb','push',str(local),'/data/local/tmp/qa-yfuse-preferences.xml'],stdout=subprocess.DEVNULL,check=True)
    cmd=f'mkdir -p {directory} && chown {uid}:{uid} {directory} && chmod 770 {directory} && cp /data/local/tmp/qa-yfuse-preferences.xml {dest} && chown {uid}:{uid} {dest} && chmod 660 {dest} && restorecon -R {directory}'
    subprocess.run(['adb','shell',cmd],check=True)
    local.unlink()
    verified=ET.fromstring(shell('shell','cat',dest))
    assert verified.find("./string[@name='player.engine']").text==engine
    print('Selected',engine,'in cloud emulator',flush=True)

def engine_evidence(engine):
    pid=shell('shell','pidof','com.yfuse').split()
    maps=shell('shell','cat','/proc/'+pid[0]+'/maps') if pid and pid[0].isdigit() else ''
    sen=('libMLibKit.so' in maps or 'libFFmpegKit.so' in maps)
    yc=('libycore_demux.so' in maps or 'libyfuse_ycore' in maps)
    raw=shell('shell','cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl 2>/dev/null | tail -n 1500')
    rows=[]
    for line in raw.splitlines():
        try: rows.append(json.loads(line))
        except (ValueError,TypeError):pass
    sen_output=any(r.get('category')=='player.senplayer' and r.get('event') in ('video_output_sample','video_output_recovery') and (r.get('attributes',{}).get('rendered')=='true' or int(r.get('attributes',{}).get('decodedFrames','0') or '0')>0) for r in rows)
    native_events=sum(str(r.get('event','')).startswith('native_direct_') for r in rows)
    return {'engine':engine,'senOriginalLibrary':sen,'ycoreLibrary':yc,'senOutputEvent':sen_output,'ycoreEvents':native_events,'active':(sen and sen_output) if engine=='SenPlayer' else ((yc or native_events>0) and not sen)}
if __name__=='__main__': configure(sys.argv[1])
