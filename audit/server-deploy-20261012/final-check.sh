#!/usr/bin/env bash
set -euo pipefail
sha=$1
stamp=$2
state_dir="/var/lib/yfuse/backups/deploy-${stamp}-${sha:0:9}"
python3 - "$sha" "$state_dir" <<'PY'
from pathlib import Path
import json, subprocess, sys, time, urllib.request
sha, state_path = sys.argv[1:]
state = Path(state_path)
with urllib.request.urlopen('https://47.112.219.60/health', timeout=15) as response:
    assert response.status == 200 and response.read().strip() == b'ok'
with urllib.request.urlopen('https://47.112.219.60/watch/version', timeout=15) as response:
    version = json.load(response)
assert version['gitSha'] == sha
with urllib.request.urlopen('http://127.0.0.1:8091/health', timeout=5) as response:
    renderer_health = json.load(response)
assert renderer_health['status'] == 'ok' and 'queuedPages' in renderer_health
script = '''
import { chromium } from 'playwright';
const browser = await chromium.launch({headless: true, timeout: 15000, args: ['--disable-dev-shm-usage', '--no-sandbox']});
try {
  const page = await browser.newPage();
  await page.setContent('<!doctype html><title>yfuse-deployment-check</title><p>renderer ready</p>');
  if (await page.title() !== 'yfuse-deployment-check') throw new Error('Unexpected rendered title');
  console.log(JSON.stringify({chromiumVersion: browser.version(), offlinePageRendered: true}));
} finally {
  await browser.close();
}
'''
process = subprocess.Popen(['docker', 'exec', '-i', 'yfuse-calendar-renderer-calendar-renderer-1', 'node', '--input-type=module'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
output, errors = process.communicate(script, timeout=30)
if process.returncode != 0:
    raise RuntimeError('Chromium smoke test failed: ' + errors)
browser = json.loads(output)
image = json.loads(subprocess.check_output(['docker', 'inspect', 'yfuse-calendar-renderer-calendar-renderer-1']))[0]
assert image['State']['Running'] and not image['State']['OOMKilled']
assert image['RestartCount'] == 0
assert subprocess.check_output(['systemctl', 'is-active', 'yfuse-update'], universal_newlines=True).strip() == 'active'
restarts = int(subprocess.check_output(['systemctl', 'show', 'yfuse-update', '-p', 'NRestarts', '--value'], universal_newlines=True).strip())
assert restarts == 0
result = {'sourceCommit': sha, 'verifiedAtUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'publicHealth': 'ok', 'watchVersion': version, 'rendererHealth': renderer_health, 'rendererBrowserSmoke': browser, 'rendererRestarts': image['RestartCount'], 'rendererOomKilled': image['State']['OOMKilled'], 'serverRestarts': restarts}
(state / 'post-deploy-verification.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2), flush=True)
PY
