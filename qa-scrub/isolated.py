import ctypes,fcntl,json,os,pathlib,socket,struct,subprocess,sys,time,urllib.request,shutil
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
def loopback():
 if [l.split(':')[0].strip() for l in open('/proc/net/dev').read().splitlines()[2:]]!=['lo']:raise RuntimeError('expected only loopback')
 if any(s.strip() and not s.startswith('Iface') for s in open('/proc/net/route')):raise RuntimeError('namespace has routes')
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
  form='16sh22x';a=struct.pack(form,b'lo',0);flags=struct.unpack(form,fcntl.ioctl(s,0x8913,a))[1];fcntl.ioctl(s,0x8914,struct.pack(form,b'lo',flags|1))
 for proto in ('http_proxy','https_proxy','all_proxy','HTTP_PROXY','HTTPS_PROXY','ALL_PROXY'):
  os.environ.pop(proto,None)
 s=socket.socket();s.settimeout(.4)
 try:s.connect(('192.0.2.1',443))
 except OSError:pass
 else:raise RuntimeError('external connection possible')
 finally:s.close()
def transport(peer,dest):
 import select
 with peer,closing(dest):
  while True:
   ready,_,_=select.select([peer,dest],[],[],45)
   if not ready:return
   for a in ready:
    data=a.recv(65536)
    if not data:return
    (dest if a is peer else peer).sendall(data)
def serve_incoming(uds):
 with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as listener:
  listener.bind(uds);listener.listen(32)
  with ThreadPoolExecutor(max_workers=12) as pool:
   while True:
    c,_=listener.accept();pool.submit(transport,c,socket.create_connection(('127.0.0.1',8096)))
def relay_inbound(uds):
 with socket.socket() as listener:
  listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);listener.bind(('127.0.0.1',18196));listener.listen(32)
  with ThreadPoolExecutor(max_workers=12) as pool:
   while True:
    c,_=listener.accept();u=socket.socket(socket.AF_UNIX);u.connect(uds);pool.submit(transport,c,u)
if __name__=='__main__':
 if sys.argv[1]=='relay':
  relay_inbound(sys.argv[2]);sys.exit()
 loopback()
 if os.environ.get('SUDO_UID'):
  os.setgroups([]);os.setgid(int(os.environ['SUDO_GID']));os.setuid(int(os.environ['SUDO_UID']))
 app=pathlib.Path(sys.argv[1]).resolve();base=pathlib.Path(sys.argv[3]).resolve();base.mkdir(exist_ok=True)
 import emby
 with open(base/'server.log','w') as log:
  env=os.environ.copy();env.update(PATH=str(app/'bin')+':'+env['PATH'],FONTCONFIG_PATH=str(app/'etc/fonts'),XDG_CACHE_HOME=str(base/'cache'))
  proc=subprocess.Popen([str(app/'system/EmbyServer'),'-programdata',str(base),'-ffdetect',str(app/'bin/ffdetect'),'-ffmpeg',str(app/'bin/ffmpeg'),'-ffprobe',str(app/'bin/ffprobe')],env=env,cwd=app,stdout=log,stderr=log)
  try:
   for _ in range(75):
    try:emby.call('/System/Info/Public');break
    except (OSError,RuntimeError):time.sleep(1)
   items,uid=emby.setup(sys.argv[2],base/'local.secret')
   records={}
   for name,id in items:
    url=emby.root+'/Videos/'+id+'/stream.mp4?Static=true'
    r=emby.op.open(emby.u.Request(url,headers={'X-Emby-Token':emby.token,'Range':'bytes=32768-36863'}))
    print(name,'HTTP',r.status,r.getheader('Content-Range'),len(r.read()),flush=True)
    from urllib.parse import urlencode,urljoin,urlparse,parse_qs
    profile={'Name':'QA','MaxStreamingBitrate':264000,'DirectPlayProfiles':[],'TranscodingProfiles':[{'Container':'ts','Type':'Video','VideoCodec':'h264','AudioCodec':'aac','Protocol':'hls','Context':'Streaming','MaxAudioChannels':'2','MinSegments':1,'SegmentLength':3}]}
    info=emby.call('/Items/'+id+'/PlaybackInfo',{'UserId':uid,'DeviceProfile':profile,'EnableDirectPlay':False,'EnableDirectStream':False,'EnableTranscoding':True,'MaxStreamingBitrate':264000,'IsPlayback':True})
    source=info['MediaSources'][0];url=source.get('TranscodingUrl')
    print('negotiation',name,'source',source.get('Id'),'transcode',source.get('SupportsTranscoding'),'url',bool(url),flush=True)
    hls_path=''
    if url:
     full=urljoin('http://127.0.0.1:8096/emby/',url.lstrip('/')) if not url.lower().startswith('/emby/') else urljoin(emby.root,url)
     if 'api_key=' not in full.lower():full+=('&' if '?' in full else '?')+'api_key='+emby.token
     try:
      with emby.op.open(full,timeout=30) as resp:master=resp.read().decode()
      if '#EXTM3U' not in master:raise ValueError('missing HLS manifest')
      hls_path=full.split(':8096',1)[1]
      print(name,'HLS master OK',flush=True)
     except Exception as err:print('HLS unavailable',type(err).__name__,getattr(err,'code',0),flush=True)
    records[name]={'hls':'http://127.0.0.1:18196'+hls_path if hls_path else None,'id':id,'server':'127.0.0.1:18196','url':'http://127.0.0.1:18196/emby/Videos/'+id+'/stream.mp4?Static=true&api_key='+emby.token}
   sec=base/'streams.json';f=os.open(sec,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600);os.write(f,json.dumps(records).encode());os.close(f)
   print('local indexed videos',len(items),flush=True)
   if len(sys.argv)>4 and sys.argv[4]=='--verify':
    from media_check import verify
    verify(sec,[78,13,92,36],base)
   elif len(sys.argv)>4:serve_incoming(sys.argv[4])
  finally:proc.terminate();proc.wait(timeout=30)
