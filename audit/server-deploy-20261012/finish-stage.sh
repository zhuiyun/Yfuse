#!/usr/bin/env bash
set -euo pipefail
stamp=$1
sha=$2
renderer_release="/opt/yfuse-calendar-renderer/releases/${stamp}-${sha:0:9}"
image="yfuse-calendar-renderer:20261012-${sha:0:9}"
smoke="yfuse-renderer-smoke-finish-${stamp}"
docker compose --progress plain -p yfuse-calendar-renderer -f "$renderer_release/compose.yaml" build calendar-renderer
cleanup() { docker rm -f "$smoke" >/dev/null 2>&1 || true; }
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
