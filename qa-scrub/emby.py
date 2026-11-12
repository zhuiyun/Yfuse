import os,pathlib,json,urllib.request as u,urllib.parse as up,urllib.error,time,secrets,sys
root='http://127.0.0.1:8096/emby'
op=u.build_opener(u.ProxyHandler({})); token=''
hdr={'X-Emby-Authorization':'MediaBrowser Client="YfuseQA", Device="QA", DeviceId="touch-test", Version="1"'}
def call(path,body=None,query=None):
 data=None if body is None else json.dumps(body).encode();h=hdr.copy()
 if token:h['X-Emby-Token']=token
 if data is not None:h['Content-Type']='application/json'
 req=u.Request(root+path+('?' + up.urlencode(query) if query else ''),data=data,headers=h)
 try:
  with op.open(req,timeout=30) as r:
   content=r.read();return json.loads(content) if content else None
 except urllib.error.HTTPError as e:raise RuntimeError(f'{path} HTTP {e.code}: {e.read()[:180]!r}') from None
def setup(media,private):
 global token
 data=pathlib.Path(private)
 secret=data.read_text() if data.exists() else secrets.token_urlsafe(20)
 if not data.exists():
  fd=os.open(data,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600);os.write(fd,secret.encode());os.close(fd)
 info=call('/System/Info/Public');print('Emby',info['Version'],'wizard',info.get('StartupWizardCompleted'),flush=True)
 if not info.get('StartupWizardCompleted'):
  call('/Startup/Configuration',{'UICulture':'en-US','MetadataCountryCode':'US','PreferredMetadataLanguage':'en'})
  call('/Startup/User',{'Name':'seekqa','Password':secret})
  call('/Startup/Complete',{})
 auth=call('/Users/AuthenticateByName',{'Username':'seekqa','Pw':secret})
 token=auth['AccessToken'];uid=auth['User']['Id']
 libs=call('/Library/VirtualFolders')
 if not any(x.get('Name')=='Seek' for x in libs):
  call('/Library/VirtualFolders',{'LibraryOptions':{'PathInfos':[{'Path':str(pathlib.Path(media).resolve())}],'EnableRealtimeMonitor':False,'EnableInternetProviders':False}}, {'Name':'Seek','CollectionType':'homevideos','RefreshLibrary':'true'})
 for _ in range(90):
  items=call('/Users/'+uid+'/Items',query={'Recursive':'true','IncludeItemTypes':'Movie,Video','Fields':'Path,MediaSources'})['Items']
  if len(items)>=2:break
  time.sleep(1)
 if len(items)<2:raise RuntimeError('Emby library did not index two videos')
 return [(x['Name'],x['Id']) for x in items],uid
if __name__=='__main__':
 items,uid=setup(sys.argv[1],sys.argv[2]);print('library',items)
 for name,id in items:
  r=op.open(u.Request(root+'/Videos/'+id+'/stream.mp4?Static=true',headers={**hdr,'X-Emby-Token':token,'Range':'bytes=32768-36863'}))
  print(name,'range',r.status,r.getheader('Content-Range'),len(r.read()))
