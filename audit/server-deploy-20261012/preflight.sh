#!/usr/bin/env bash
set -euo pipefail
hostname
id
systemctl is-active yfuse-update.service
readlink -f /opt/yfuse-watch/current
systemctl show yfuse-update.service -p FragmentPath -p ExecStart -p WorkingDirectory -p User -p EnvironmentFiles --no-pager
command -v sqlite3
df -h /opt /var/lib/yfuse
python3 - <<'PY'
from pathlib import Path
path = Path('/etc/yfuse-watch/environment')
print('environment_file_present=' + str(path.is_file()).lower())
if path.is_file():
    for line in path.read_text().splitlines():
        name, separator, value = line.partition('=')
        if separator and name in ('TMDB_TOKEN', 'CALENDAR_SIGNING_PRIVATE_KEY', 'MIGRATION_RELAY_MASTER_KEY', 'YFUSE_RENDERER_TOKEN', 'CALENDAR_INGESTION_CONFIG_PATH'):
            print(name + '_configured=' + str(bool(value.strip())).lower())
PY
if command -v docker >/dev/null; then
    docker ps --format '{{.Names}} {{.Image}} {{.Status}}'
    for container in $(docker ps --format '{{.Names}}' | sed -n '/calendar\|renderer/p'); do
        docker inspect "$container" --format '{{.Name}} working_dir={{index .Config.Labels "com.docker.compose.project.working_dir"}} config={{index .Config.Labels "com.docker.compose.project.config_files"}} image_id={{.Image}}'
    done
fi
find /opt/yfuse-watch -maxdepth 3 -type f \( -name compose.yaml -o -name docker-compose.yml -o -name calendar-ingestion.json \) -print
