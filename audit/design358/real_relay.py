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
    except Exception as e:result['check_problem']=type(e).__name__
    save('connectivity.json',result)
    print('Server reachability check finished.')
    raise SystemExit()
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
        try:
            r=requests.request(method,base+self.path,headers=headers,data=body,stream=True,timeout=(15,40),allow_redirects=False)
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
if sys.argv[1]=='serve':ThreadingHTTPServer(('127.0.0.1',18096),Relay).serve_forever()
