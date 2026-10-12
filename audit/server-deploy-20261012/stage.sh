#!/usr/bin/env bash
set -euo pipefail
server_archive=$1
server_hash=$2
renderer_archive=$3
renderer_hash=$4
sha=$5
stamp=$6
server_release="/opt/yfuse-watch/releases/${stamp}-${sha:0:9}-backend"
renderer_release="/opt/yfuse-calendar-renderer/releases/${stamp}-${sha:0:9}"
state_dir="/var/lib/yfuse/backups/deploy-${stamp}-${sha:0:9}"
previous_server=$(readlink -f /opt/yfuse-watch/current)
previous_renderer=$(readlink -f /opt/yfuse-calendar-renderer/current)
image="yfuse-calendar-renderer:20261012-${sha:0:9}"
smoke="yfuse-renderer-smoke-${stamp}"
test "$(id -u)" = 0
test -L /opt/yfuse-watch/current
test -L /opt/yfuse-calendar-renderer/current
test "$(sha256sum "$server_archive" | cut -d ' ' -f 1)" = "$server_hash"
test "$(sha256sum "$renderer_archive" | cut -d ' ' -f 1)" = "$renderer_hash"
test ! -e "$server_release"
test ! -e "$renderer_release"
install -d -m 0755 "$server_release" "$renderer_release"
install -d -m 0700 "$state_dir"
tar xzf "$server_archive" -C "$server_release" --strip-components=1
tar xzf "$renderer_archive" -C "$renderer_release" --strip-components=1
chown -R root:root "$server_release" "$renderer_release"
test -x "$server_release/bin/watchTogetherServer"
python3 - "$server_release" "$sha" <<'PY'
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1])
jar=next(root.joinpath('lib').glob('watchTogetherServer*.jar'))
with zipfile.ZipFile(str(jar)) as archive:
    assert 'gitSha='+sys.argv[2] in archive.read('build-info.properties').decode()
    assert 'com/yfuse/watch/CalendarScheduleMergeKt.class' in archive.namelist()
print('backend_archive_verified=true',flush=True)
PY
install -m 0600 "$previous_renderer/renderer.environment" "$renderer_release/renderer.environment"
cp -a "$previous_renderer/renderer-state" "$renderer_release/renderer-state"
python3 - "$state_dir" "$server_release" "$renderer_release" "$previous_server" "$previous_renderer" "$sha" "$server_hash" "$renderer_hash" <<'PY'
from pathlib import Path
import json,subprocess,sys
state=Path(sys.argv[1])
old=json.loads(subprocess.check_output(['docker','inspect','yfuse-calendar-renderer-calendar-renderer-1']))[0]
tokens=[v.split('=',1)[1] for v in old['Config'].get('Env',[]) if v.startswith(('YFUSE_CALENDAR_RENDERER_TOKEN=','YFUSE_RENDERER_TOKEN='))]
assert any(len(token)>=24 for token in tokens),'Existing renderer token is not compatible'
data={'sourceCommit':sys.argv[6],'serverRelease':sys.argv[2],'rendererRelease':sys.argv[3],'previousServer':sys.argv[4],'previousRenderer':sys.argv[5],'previousRendererImageId':old['Image'],'previousRendererImageTag':old['Config']['Image'],'serverArchiveSha256':sys.argv[7],'rendererArchiveSha256':sys.argv[8]}
state.joinpath('deployment.json').write_text(json.dumps(data,indent=2)+'\n')
print('existing_renderer_configuration_preserved=true',flush=True)
PY
printf 'building_renderer_image=%s\n' "$image"
docker compose -p yfuse-calendar-renderer -f "$renderer_release/compose.yaml" build --progress plain calendar-renderer
cleanup() {
    docker rm -f "$smoke" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker run --detach --name "$smoke" --init --memory 768m --shm-size 256mb \
    --security-opt no-new-privileges:true -p 127.0.0.1:18091:8091 \
    --env-file "$renderer_release/renderer.environment" \
    --env YFUSE_RENDERER_HOST=0.0.0.0 --env YFUSE_RENDERER_PORT=8091 \
    --env YFUSE_RENDERER_STORAGE_STATE=/var/lib/yfuse-renderer/storage-state.json \
    --mount "type=bind,source=$renderer_release/renderer-state,target=/var/lib/yfuse-renderer,readonly" "$image"
python3 - "$smoke" <<'PY'
import json,subprocess,sys,time,urllib.request,urllib.error
base='http://127.0.0.1:18091'
for attempt in range(30):
    try:
        with urllib.request.urlopen(base+'/health',timeout=2) as response:
            health=json.load(response)
        assert health['status']=='ok' and health['activePages']==0 and health['queuedPages']==0
        break
    except Exception:
        time.sleep(1)
else:
    raise RuntimeError('Staged renderer did not become healthy')
config=json.loads(subprocess.check_output(['docker','inspect',sys.argv[1]]))[0]['Config']
token=next(value.split('=',1)[1] for value in config['Env'] if value.startswith(('YFUSE_CALENDAR_RENDERER_TOKEN=','YFUSE_RENDERER_TOKEN=')))
def status(body,authorized):
    request=urllib.request.Request(base+'/v1/render',data=json.dumps(body).encode(),headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if authorized else {})})
    try:
        with urllib.request.urlopen(request,timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
assert status({'url':'https://weibo.com/'},False)==401
assert status({'url':'http://127.0.0.1/'},True) in (400,403)
version=subprocess.check_output(['docker','exec',sys.argv[1],'node','-p','require("playwright/package.json").version'],universal_newlines=True).strip()
assert version=='1.63.0'
print('staged_renderer_health_auth_and_network_policy_verified=true',flush=True)
print('staged_renderer_playwright='+version,flush=True)
PY
printf 'STAGED_SERVER=%s\nSTAGED_RENDERER=%s\nDEPLOYMENT_STATE=%s\n' "$server_release" "$renderer_release" "$state_dir"
