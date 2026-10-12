#!/usr/bin/env bash
set -euo pipefail
umask 077
sha=$1
stamp=$2
mode=${3:-all}
test "$mode" = all || test "$mode" = backend
state_dir="/var/lib/yfuse/backups/deploy-${stamp}-${sha:0:9}"
exec 9>/run/lock/yfuse-backend-deploy.lock
flock -n 9
test "$(id -u)" = 0
test -s "$state_dir/deployment.json"
readarray -t paths < <(python3 - "$state_dir/deployment.json" <<'PY'
import json,sys
data=json.load(open(sys.argv[1]))
for name in ('serverRelease','rendererRelease','previousServer','previousRenderer','previousRendererImageId'):
    print(data[name])
PY
)
server_release=${paths[0]}
renderer_release=${paths[1]}
previous_server=${paths[2]}
previous_renderer=${paths[3]}
previous_image=${paths[4]}
case "$server_release:$previous_server" in /opt/yfuse-watch/releases/*:/opt/yfuse-watch/releases/*) ;; *) exit 1 ;; esac
case "$renderer_release:$previous_renderer" in /opt/yfuse-calendar-renderer/releases/*:/opt/yfuse-calendar-renderer/releases/*) ;; *) exit 1 ;; esac
backend_already_deployed=0
if [ "$(readlink -f /opt/yfuse-watch/current)" = "$server_release" ]; then
    backend_already_deployed=1
else
    test "$(readlink -f /opt/yfuse-watch/current)" = "$previous_server"
fi
test "$(readlink -f /opt/yfuse-calendar-renderer/current)" = "$previous_renderer"
test "$(docker inspect yfuse-calendar-renderer-calendar-renderer-1 --format '{{.Image}}')" = "$previous_image"
test ! -e "/opt/yfuse-watch/current.next-${stamp}"
test ! -e "/opt/yfuse-calendar-renderer/current.next-${stamp}"
if [ "$backend_already_deployed" = 0 ]; then
python3 - "$state_dir" <<'PY'
from pathlib import Path
import hashlib,json,sqlite3,subprocess,sys,time
state=Path(sys.argv[1])
backup=state/'database-backups'/time.strftime('%Y%m%d-%H%M%S',time.gmtime())
backup.mkdir(parents=True,mode=0o700)
for source in sorted(Path('/var/lib/yfuse').glob('*.db')):
    target=backup/source.name
    assert not target.exists()
    subprocess.check_call(['sqlite3','-cmd','.timeout 10000',str(source),".backup '"+str(target)+"'"])
    with sqlite3.connect(str(target)) as connection:
        assert connection.execute('PRAGMA integrity_check').fetchall()==[('ok',)]
    target.chmod(0o600)
    print('database_backup_verified='+source.name,flush=True)
files={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in Path('/srv/yfuse-update/yfuse').glob('update*.json')}
state.joinpath('latest-database-backup-path.txt').write_text(str(backup)+'\n')
manifest_path=state/'apk-update-manifests-before.json'
if manifest_path.exists():
    assert json.loads(manifest_path.read_text())==files
else:
    manifest_path.write_text(json.dumps(files,indent=2)+'\n')
assert (backup/'account.db').exists()
PY
fi
server_switched=0
renderer_switched=0
rollback() {
    result=$?
    if [ "$result" -ne 0 ]; then
        set +e
        if [ "$server_switched" = 1 ]; then
            ln -s "$previous_server" "/opt/yfuse-watch/current.rollback-${stamp}"
            mv -Tf "/opt/yfuse-watch/current.rollback-${stamp}" /opt/yfuse-watch/current
            systemctl restart yfuse-update
            printf 'ROLLED_BACK_SERVER=%s\n' "$previous_server"
        fi
        if [ "$renderer_switched" = 1 ]; then
            docker compose -p yfuse-calendar-renderer -f "$previous_renderer/compose.yaml" up -d --no-build calendar-renderer
            ln -s "$previous_renderer" "/opt/yfuse-calendar-renderer/current.rollback-${stamp}"
            mv -Tf "/opt/yfuse-calendar-renderer/current.rollback-${stamp}" /opt/yfuse-calendar-renderer/current
            printf 'ROLLED_BACK_RENDERER=%s\n' "$previous_renderer"
        fi
        printf 'DEPLOYMENT_FAILED=%s\n' "$result"
    fi
    exit "$result"
}
trap rollback EXIT
if [ "$mode" = all ]; then
renderer_switched=1
docker compose -p yfuse-calendar-renderer -f "$renderer_release/compose.yaml" up -d --no-build calendar-renderer
ln -s "$renderer_release" "/opt/yfuse-calendar-renderer/current.next-${stamp}"
mv -Tf "/opt/yfuse-calendar-renderer/current.next-${stamp}" /opt/yfuse-calendar-renderer/current
python3 - <<'PY'
import json,time,urllib.request
for attempt in range(30):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8091/health',timeout=2) as response:
            data=json.load(response)
        assert data['status']=='ok' and 'queuedPages' in data
        print('production_renderer_healthy=true',flush=True)
        break
    except Exception:
        time.sleep(1)
else:
    raise RuntimeError('Production renderer did not become healthy')
PY
fi
if [ "$backend_already_deployed" = 0 ]; then
server_switched=1
ln -s "$server_release" "/opt/yfuse-watch/current.next-${stamp}"
mv -Tf "/opt/yfuse-watch/current.next-${stamp}" /opt/yfuse-watch/current
systemctl restart yfuse-update
systemctl is-active --quiet yfuse-update
fi
python3 - "$sha" "$state_dir" "$mode" <<'PY'
from pathlib import Path
import hashlib,json,subprocess,sys,time,urllib.request,urllib.error
sha=sys.argv[1]
state=Path(sys.argv[2])
mode=sys.argv[3]
checks=[]
def request(url,method='GET',body=None,headers=None):
    r=urllib.request.Request(url,method=method,data=body,headers=headers or {})
    try:
        with urllib.request.urlopen(r,timeout=15) as response:
            return response.status,response.read()
    except urllib.error.HTTPError as error:
        return error.code,error.read()
for base in ('http://127.0.0.1:8080','https://47.112.219.60'):
    status,data=request(base+'/health')
    assert status==200 and data.strip()==b'ok'
    status,data=request(base+'/watch/version')
    version=json.loads(data)
    assert status==200 and version['gitSha']==sha
    assert version['protocolVersion']==6 and version['minProtocolVersion']==5
    headers={'X-Forwarded-Proto':'https'} if base.startswith('http:') else {}
    for path,method,body in (
        ('/api/v1/tmdb/movie/603','GET',None),
        ('/api/v1/account/handoff','GET',None),
        ('/api/v1/account/handoff/heartbeat','POST',b'{}'),
        ('/api/v1/account/profile','GET',None),
    ):
        status,_=request(base+path,method,body,{**headers,**({'Content-Type':'application/json'} if body else {})})
        assert status==401,(base,path,status)
        checks.append({'url':base+path,'status':status})
        print('verified_route='+base+path+' status='+str(status),flush=True)
    status,data=request(base+'/api/v1/calendar/schedules')
    assert status==200,(base,'calendar',status)
    calendar=json.loads(data)
    assert calendar.get('signature') and calendar.get('payload') and calendar.get('revision')
    checks.append({'url':base+'/api/v1/calendar/schedules','status':status,'revision':calendar['revision']})
status,_=request('http://47.112.219.60/api/v1/tmdb/movie/603')
assert status==426,('plaintext_api',status)
image=json.loads(subprocess.check_output(['docker','inspect','yfuse-calendar-renderer-calendar-renderer-1']))[0]
if mode=='all':
    assert '20261012-'+sha[:9] in image['Config']['Image']
assert image['State']['Running']
playwright=subprocess.check_output(['docker','exec','yfuse-calendar-renderer-calendar-renderer-1','node','-p','require("playwright/package.json").version'],universal_newlines=True).strip()
if mode=='all':
    assert playwright=='1.63.0'
before=json.loads(state.joinpath('apk-update-manifests-before.json').read_text())
after={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in Path('/srv/yfuse-update/yfuse').glob('update*.json')}
assert before==after,'APK update manifests unexpectedly changed'
time.sleep(10)
assert subprocess.check_output(['systemctl','is-active','yfuse-update'],universal_newlines=True).strip()=='active'
restart_count=int(subprocess.check_output(['systemctl','show','yfuse-update','-p','NRestarts','--value'],universal_newlines=True).strip())
assert restart_count==0
result=json.loads(state.joinpath('deployment.json').read_text())
backup_path=Path((state/'latest-database-backup-path.txt').read_text().strip())
result.update({'deployed':True,'rendererUpdated':mode=='all','verifiedAtUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'routeChecks':checks,'plaintextAccountApiStatus':426,'rendererPlaywright':playwright,'rendererImageId':image['Image'],'serviceRestarts':restart_count,'apkUpdateManifestsUnchanged':True,'apkPublished':False,'databaseBackups':[path.name for path in backup_path.glob('*.db')],'databaseBackupPath':str(backup_path),'backupPath':str(state)})
state.joinpath('verification.json' if mode=='all' else 'backend-verification.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2),flush=True)
PY
printf 'DEPLOYMENT_VERIFIED=true\n'
