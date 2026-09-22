from pathlib import Path
import json
root=Path('harmonyApp/entry/src/main/cangjie/src')
for p in root.rglob('*.cj'):
 s=p.read_text(encoding='utf-8');n=s.replace('import encoding.', 'import stdx.encoding.')
 if n!=s:p.write_text(n,encoding='utf-8')
p=Path('scripts/verify-cangjie-host.py');s=p.read_text(encoding='utf-8').replace('encoding.json','stdx.encoding.json').replace('encoding.url','stdx.encoding.url');s=s.replace('r"can not find package \'(encoding\\.[a-z]+)\'"','r"can not find package \'((?:stdx\\.)?encoding\\.[a-z]+)\'"');s=s.replace('''    env = toolchain_env(home)
''','''    env = toolchain_env(home)
    stdx = os.environ.get("CANGJIE_STDX_PATH", "")
    stdx_args = ["--import-path", stdx] if stdx and Path(stdx).is_dir() else []
''');s=s.replace('''"--import-path", str(out), "-o",''','''"--import-path", str(out), *stdx_args, "-o",''');p.write_text(s,encoding='utf-8')
p=Path('parity/implementation-coverage.json');d=json.loads(p.read_text(encoding='utf-8'));d['statusVocabulary']['sourceWired']='UI and production adapters are connected in source, but matching SDK compilation and running-build evidence are missing.';d['summary']['reviewedAt']='2026-09-10';d['summary']['note']='Emby/Jellyfin login, Native Preferences metadata persistence, real item lists/details, same-origin system playback URLs and local/server progress calls are now wired through ConnectedMediaScreen. This source slice has NOT been compiled as a HAP or verified on Harmony hardware. Host tests cover 33 existing pure-logic cases; network/provider/storage/support still require stdx and matching UI SDK. Other feature gaps remain listed; no feature is promoted to implemented.'
notes={
'provider.emby':'ConnectedMediaScreen calls login, paginated item search, detail and playback source resolution. Same-origin playback URL authorization is applied. Requires matching Cangjie SDK/stdx build and server/device validation.',
'provider.jellyfin':'Connected to the same real login/library/detail/playback slice with a distinct provider selection. Requires matching Cangjie SDK/stdx build and server/device validation.',
'server.multi':'NativeStringPreferenceStore now supplies durable metadata through native Preferences with close/reopen verification. Asset Store holds tokens. Reauthentication stages a fresh secret and rolls back metadata/default on failure. Requires HAP and restart validation.',
'sync.playback':'System Video updates write a bounded, server/item-isolated local checkpoint and report progress every 15 media seconds plus pause/exit. Network failures remain visible. Offline pending server retries and account-wide sync remain incomplete; SDK compilation is unverified.'
}
for f in d['features']:
 if f['id'] in notes:
  f['status']='sourceWired';f['note']=notes[f['id']]
  f['sources']+=['harmonyApp/entry/src/main/cangjie/src/ui/connected_media_screen.cj','harmonyApp/entry/src/main/cangjie/src/provider/connected_session.cj']
  if f['id']=='server.multi':f['sources']+=['harmonyApp/entry/src/main/cangjie/src/storage/native_preferences.cj','harmonyApp/entry/src/main/cpp/ypreferences.cpp']
  if f['id']=='sync.playback':f['sources']+=['harmonyApp/entry/src/main/cangjie/src/storage/playback_progress.cj','harmonyApp/entry/src/main/cangjie/src/ui/player_screen.cj']
p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
p=Path('scripts/verify-harmony-port.py');s=p.read_text(encoding='utf-8').replace('        "physicalValidation",','        "physicalValidation",\n        "sourceWired",');p.write_text(s,encoding='utf-8')
