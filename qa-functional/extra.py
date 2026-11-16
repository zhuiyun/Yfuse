import hashlib,http.server,os,pathlib,re,socketserver,sys,threading,time,urllib.parse,xml.etree.ElementTree as ET
from features import adb,shell,snapshot,nodes,labels,find,grab,wait,expect,root,tap,type_into,field,step,launch,pressback,hide_keyboard,pkg
rootdir=pathlib.Path('qa-media');clip=(rootdir/'ProbeA.mp4').read_bytes()
def body(data,typ,request,status=200):
 request.send_response(status);request.send_header('Content-Type',typ);request.send_header('Content-Length',str(len(data)));request.end_headers()
 if request.command!='HEAD':request.wfile.write(data)
class Server(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  if self.path.startswith('/list.m3u'):return body(b'#EXTM3U\n#EXTINF:-1 group-title="QA",Cloud Channel A\nhttp://127.0.0.1:18197/clip.mp4\n#EXTINF:-1 group-title="QA",Cloud Channel B\nhttp://127.0.0.1:18197/clip.mp4\n','audio/x-mpegurl',self)
  if self.path.endswith('.mp4'):
   m=re.fullmatch(r'bytes=(\d+)-(\d*)',self.headers.get('Range',''))
   if m:
    a=int(m[1]);b=min(int(m[2]) if m[2] else len(clip)-1,len(clip)-1)
    self.send_response(206);self.send_header('Accept-Ranges','bytes');self.send_header('Content-Type','video/mp4');self.send_header('Content-Range',f'bytes {a}-{b}/{len(clip)}');self.send_header('Content-Length',str(b-a+1));self.end_headers()
    if self.command!='HEAD':self.wfile.write(clip[a:b+1])
    return
   return body(clip,'video/mp4',self)
  return body(b'Not Found','text/plain',self,404)
 do_HEAD=do_GET
 def do_OPTIONS(self):
  self.send_response(200);self.send_header('DAV','1');self.send_header('Allow','OPTIONS,GET,HEAD,PROPFIND');self.send_header('Content-Length','0');self.end_headers()
 def do_PROPFIND(self):
  if int(self.headers.get('Content-Length','0')):self.rfile.read(int(self.headers.get('Content-Length')))
  def prop(href,name,isdir):
   return '<D:response><D:href>'+href+'</D:href><D:propstat><D:prop><D:displayname>'+name+'</D:displayname><D:resourcetype>'+('<D:collection/>' if isdir else '')+'</D:resourcetype><D:getcontentlength>'+str(0 if isdir else len(clip))+'</D:getcontentlength><D:getcontenttype>'+('httpd/unix-directory' if isdir else 'video/mp4')+'</D:getcontenttype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>'
  xml='<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">'+prop('/dav/','dav',True)+(prop('/dav/clip.mp4','clip.mp4',False) if self.headers.get('Depth')!='0' else '')+'</D:multistatus>'
  body(xml.encode(),'application/xml',self,207)
http=http.server.ThreadingHTTPServer(('127.0.0.1',18197),Server);threading.Thread(target=http.serve_forever,daemon=True).start();adb('reverse','tcp:18197','tcp:18197')
def open_server_entry(keys):
 root('服务器');tap(grab(keys,6))
def iptv_import():
 open_server_entry('IPTV · 电视直播');wait('M3U 地址')
 field('订阅名称','QAChannels');field('M3U 地址','http://127.0.0.1:18197/list.m3u');tap(grab('添加订阅',3))
 return expect('Cloud Channel A')
def iptv_filter():
 field('搜索频道','Channel%sB',True)
 wait('Cloud Channel B');t=snapshot();assert not any('Cloud Channel A' in labels(n) for n in nodes(t))
 return 'only Channel B'
def wifi_send():
 launch();open_server_entry('Wi-Fi 导入视频');tap(grab('开启临时接收',3))
 for _ in range(12):
  t=snapshot()
  permit=find(t,['允许','Allow','While using the app','使用应用时允许'])
  if permit is not None:tap(permit);continue
  words=' '.join(w for n in nodes(t) for w in labels(n));link=re.search(r'http://([\d.]+):(\d+)',words);code=re.search(r'配对码[：:]\s*(\d+)',words)
  if link and code:break
  time.sleep(.5)
 else:raise RuntimeError('temporary receiver not available')
 import subprocess
 host,port=link.groups();data=clip;name='Imported-QA.mp4'
 request=(f'PUT /upload?name={name} HTTP/1.1\r\nHost: {host}:{port}\r\nContent-Type: video/mp4\r\nX-Yfuse-Pair-Code: {code[1]}\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n').encode()+data
 r=subprocess.run(['adb','shell','-T','toybox','nc','-w','12',host,port],input=request,capture_output=True,timeout=22)
 first=r.stdout.split(b'\r\n')[0]
 assert re.search(rb'HTTP/1.[01] 20[01]',first),str(first[:60])
 wait(name);tap(grab('停止接收',2));return expect('开启临时接收')
def dav():
 launch();open_server_entry('添加文件来源');tap(grab(['WebDAV','WebDav'],3))
 field('地址','http://127.0.0.1:18197/dav',True);field('显示名称','QA-DAV',True)
 tap(grab('连接并添加',6));return expect('QA-DAV')
def dav_browse():
 root('服务器');tap(grab('QA-DAV',5));return expect('clip.mp4')
launch()
for name,fn in [('iptv-import-real-m3u',iptv_import),('iptv-channel-filter',iptv_filter),('wifi-import-real-video',wifi_send),('webdav-add-source',dav),('webdav-list-remote-file',dav_browse)]:step(name,fn)
http.shutdown()
