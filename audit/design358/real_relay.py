import os,json,sys,threading
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlsplit
import requests
temp=Path(os.environ['RUNNER_TEMP']);d=temp/'private-review';d.mkdir(mode=0o700,exist_ok=True);cfg=json.loads((temp/'real-config.json').read_text())
base=cfg['url'].rstrip('/')
def save(k,v): (d/k).write_text(json.dumps(v,ensure_ascii=False))
if sys.argv[1]=='check':
    result={}; sess=requests.Session();sess.trust_env=False;sess.headers.update({'X-Emby-Authorization':'MediaBrowser Client="Yfuse design review", Device="Cloud Android", DeviceId="design-review-'+os.environ['GITHUB_RUN_ID']+'", Version="2.0.6"'})
    try:
        r=sess.get(base+'/System/Info/Public',timeout=(12,16),allow_redirects=False);result['public']=r.status_code
        if r.is_redirect:raise ValueError('unexpected origin redirect')
        r=sess.post(base+'/Users/AuthenticateByName',json={'Username':cfg['user'],'Pw':cfg['password']},timeout=(12,18),allow_redirects=False);result['auth']=r.status_code
        r.raise_for_status();res=r.json();uid=res['User']['Id'];sess.headers['X-Emby-Token']=res['AccessToken']
        views=sess.get(base+'/Users/'+uid+'/Views',timeout=18).json()['Items'];result['views']=[x['Name'] for x in views[:22]]
        samples={}
        for t in ['Movie','Series']:
            r=sess.get(base+'/Users/'+uid+'/Items',params={'Recursive':'true','IncludeItemTypes':t,'Fields':'ProductionYear','SortBy':'SortName','Limit':12},timeout=20)
            data=r.json();result[t+'Exists']=bool(data.get('Items'));samples[t]=[{'id':x['Id'],'name':x['Name']} for x in data.get('Items',[]) if x.get('Name')][:8]
        save('candidates.json',samples)
        # Material is transient and excluded from the evidence ZIP.
        media=sess.get(base+'/Users/'+uid+'/Items',params={'Limit':50,'Recursive':'true','IncludeItemTypes':'Movie,Episode','Fields':'MediaSources,MediaStreams,Path','SortBy':'DateCreated','SortOrder':'Descending'},timeout=18).json().get('Items',[])
        def rank(it):
            ss=it.get('MediaSources') or []; src=ss[0] if ss else {}; streams=src.get('MediaStreams') or it.get('MediaStreams') or [];vs=next((v for v in streams if v.get('Type')=='Video'),{})
            return (vs.get('Codec','').lower() in ['avc','h264'],(vs.get('Width') or 9999)<=1920, str(src.get('Container') or '').lower() in ['mp4','mkv'],bool(ss))
        chosen=max(media,key=rank) if media else None
        if chosen:
            (temp/'real-movie-session').write_text(json.dumps({'uid':uid,'token':res['AccessToken'],'id':chosen['Id'],'media':((chosen.get('MediaSources') or [{}])[0].get('Id') or chosen['Id'])}))
            result['sample_selected']=True
    except Exception as e:result['check_problem']=type(e).__name__
    save('connectivity.json',result)
    print('Server reachability check finished.')
    raise SystemExit()
counts={};lock=threading.Lock()
def count(k):
    with lock:
        counts[k]=counts.get(k,0)+1
        try:save('relay-counts.json',counts)
        except Exception:pass
class Relay(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def forward(self):
        path=urlsplit(self.path).path.lower();method=self.command
        if not self.path.startswith('/') or self.path.startswith('//'):self.send_error(400);return
        length=int(self.headers.get('Content-Length',0));body=self.rfile.read(length) if length else None
        if method not in ['GET','HEAD'] and not (method=='POST' and (path.endswith('/authenticatebyname') or path.endswith('/playbackinfo') or path.endswith('/items/query'))):
            self.send_response(204);self.end_headers();return
        headers={k:v for k,v in self.headers.items() if k.lower() not in ['connection','accept-encoding','host','transfer-encoding','content-length','proxy-connection']}
        headers['Accept-Encoding']='identity'
        url=base+self.path;params=None
        if path.endswith('/authenticatebyname') and method=='POST':
            body=json.dumps({'Username':cfg['user'],'Pw':cfg['password']}).encode();headers['Content-Type']='application/json'
        if path=='/review/video.mp4':
            try:
                v=json.loads((temp/'real-movie-session').read_text());url=base+'/Videos/'+v['id']+'/stream';params={'static':'true','MediaSourceId':v['media'],'UserId':v['uid'],'api_key':v['token']};headers['X-Emby-Token']=v['token']
            except Exception:self.send_error(404);return
        try:
            r=requests.request(method,url,params=params,headers=headers,data=body,stream=True,timeout=(15,40),allow_redirects=False)
            category=('login' if 'authenticatebyname' in path else 'sample-stream' if path=='/review/video.mp4' else 'views' if path.endswith('/views') else 'library-query' if '/items' in path else 'session')
            count(category+'-'+str(r.status_code))
            self.send_response(r.status_code)
            for k,v in r.headers.items():
                if k.lower() not in ['connection','keep-alive','transfer-encoding','proxy-authenticate','upgrade','server','date']:self.send_header(k,v)
            self.end_headers()
            if method!='HEAD':
                for c in r.raw.stream(65536,decode_content=False):
                    if c:self.wfile.write(c)
            r.close()
        except Exception:
            try:self.send_error(502)
            except Exception:pass
    do_GET=do_HEAD=do_POST=do_DELETE=do_PUT=do_PATCH=forward
if sys.argv[1]=='serve':ThreadingHTTPServer(('127.0.0.1',8096),Relay).serve_forever()
