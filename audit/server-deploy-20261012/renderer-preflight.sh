#!/usr/bin/env bash
set -euo pipefail
printf 'renderer_current='
readlink -f /opt/yfuse-calendar-renderer/current || true
docker inspect yfuse-calendar-renderer-calendar-renderer-1 | python3 -c '
import json,sys
c=json.load(sys.stdin)[0]
print("project="+c["Config"]["Labels"].get("com.docker.compose.project", ""))
print("image="+c["Image"])
for item in c.get("Mounts", []):
    print("mount="+item["Source"]+" -> "+item["Destination"])
print("renderer_token_configured="+str(any(value.startswith("YFUSE_RENDERER_TOKEN=") and len(value.split("=",1)[1])>=24 for value in c["Config"].get("Env",[]))).lower())
'
docker exec yfuse-calendar-renderer-calendar-renderer-1 node -p 'require("playwright/package.json").version'
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8091/health
printf '\n'
find /opt/yfuse-calendar-renderer -maxdepth 3 -type f \( -name compose.yaml -o -name renderer.environment \) -printf '%m %p\n'
systemctl show yfuse-update -p MainPID -p NRestarts --no-pager
python3 - <<'PY'
from pathlib import Path
p=Path('/etc/yfuse-watch/environment')
for line in p.read_text().splitlines():
    name,sep,value=line.partition('=')
    if sep and ('CALENDAR' in name or 'RENDERER' in name):
        print('calendar_environment_key='+name)
PY
