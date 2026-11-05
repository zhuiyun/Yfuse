import hashlib, json, pathlib, sys, zipfile
root = pathlib.Path(__file__).resolve().parents[1]
apk = pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path(r'C:\Users\app-inkbird\Downloads\SenPlayer.apk')
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
expected = '45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c'
if sha != expected:
    raise SystemExit('APK differs from the reviewed source. Review the new ABI and license before importing.')
out = root/'senplayerBridge/src/main/assets/senplayer'
out.mkdir(parents=True, exist_ok=True)
files = {'classes.dex':'classes.dex','assets/license.json':'license.json'}
for name in ('libFFmpegKit.so','libMLibKit.so','libc++_shared.so'):
    files['lib/arm64-v8a/'+name] = 'native/'+name
manifest={'sourceApkSha256':sha,'abi':'arm64-v8a','files':[]}
with zipfile.ZipFile(apk) as z:
    for entry,target in files.items():
        data=z.read(entry);path=out/target;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        manifest['files'].append({'asset':target,'apkEntry':entry,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
(out/'runtime.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print(json.dumps(manifest,indent=2))
