"""Independent, bounded cloud HTTP probes for a public original that stalled before headers."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.parse


def record(url, output):
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Cloud runner required')
    if urllib.parse.urlsplit(url).hostname not in ('download.blender.org', 'media.xiph.org', 'video.blender.org', 'images-assets.nasa.gov'):
        raise ValueError('Public catalog host required')
    rows = []
    output = Path(output)
    with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as temporary:
        directory = Path(temporary)
        for protocol in ('--http1.1', '--http2'):
            for start, length in ((0, 1024), (0, 131072), (2097152, 131072), (4194304, 131072)):
                body, headers = directory / 'body', directory / 'headers'
                result = subprocess.run([
                    'curl', protocol, '--silent', '--show-error', '--location', '--fail',
                    '--connect-timeout', '8', '--max-time', '20', '--max-filesize', '2097152',
                    '--range', f'{start}-{start + length - 1}', '-H', 'Accept-Encoding: identity',
                    '--output', str(body), '--dump-header', str(headers), '--write-out', '%{json}', url,
                ], capture_output=True, timeout=25)
                try:
                    details = json.loads(result.stdout)
                except ValueError:
                    details = {}
                data = body.read_bytes() if body.exists() else b''
                row = {'requestedProtocol': protocol, 'start': start, 'length': length,
                       'exitCode': result.returncode, 'returnedBytes': len(data),
                       'bodySha256': hashlib.sha256(data).hexdigest() if data else None,
                       **{key: details.get(key) for key in ('http_code', 'http_version', 'time_connect', 'time_starttransfer', 'time_total')}}
                row['responseHeaders'] = [line for line in headers.read_text(errors='replace').splitlines()
                                          if line.lower().startswith(('http/', 'content-range:', 'content-length:', 'accept-ranges:', 'age:', 'x-cache:', 'via:'))] if headers.exists() else []
                rows.append(row)
                output.write_text(json.dumps(rows, indent=2), encoding='utf-8')
                print('ORIGINAL_RANGE_CONTROL', json.dumps(row), flush=True)
                body.unlink(missing_ok=True)
                headers.unlink(missing_ok=True)
