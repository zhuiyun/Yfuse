"""Import the exact official MCR image with bounded parallel HTTP ranges (Python 3.6)."""

from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tarfile
import time
import urllib.request

BASE = 'https://mcr.microsoft.com/v2/playwright/'
TAG = 'mcr.microsoft.com/playwright:v1.63.0-noble'
INDEX = 'sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27'
ROOT = Path('/opt/yfuse-calendar-renderer/downloads/playwright-1.63.0')
ROOT.mkdir(parents=True, exist_ok=True)
CHUNK = 1024 * 1024


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def fetch(path, expected):
    name = expected.split(':')[1]
    cached = ROOT / (name + '.metadata')
    for candidate in (cached, Path('/var/lib/docker/buildkit/content/blobs/sha256') / name, Path('/var/lib/docker/containerd/daemon/io.containerd.content.v1.content/blobs/sha256') / name):
        if candidate.is_file():
            data = candidate.read_bytes()
            if digest(data) == expected:
                print('verified_official_metadata_cache=' + expected, flush=True)
                return data
    for attempt in range(3):
        try:
            request = urllib.request.Request(BASE + path, headers={'Accept': 'application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json'})
            with urllib.request.urlopen(request, timeout=45) as response:
                data = response.read()
            assert digest(data) == expected, 'Official registry metadata digest mismatch'
            cached.write_bytes(data)
            return data
        except Exception:
            if attempt == 2:
                raise
            print('registry_metadata_retry=' + expected, flush=True)
            time.sleep(attempt + 1)


index = json.loads(fetch('manifests/' + INDEX, INDEX))
descriptor = next(item for item in index['manifests'] if item['platform']['os'] == 'linux' and item['platform']['architecture'] == 'amd64')
manifest_bytes = fetch('manifests/' + descriptor['digest'], descriptor['digest'])
manifest = json.loads(manifest_bytes)
config_bytes = fetch('blobs/' + manifest['config']['digest'], manifest['config']['digest'])
config = json.loads(config_bytes)
assert config['os'] == 'linux' and config['architecture'] == 'amd64'
assert len(config['rootfs']['diff_ids']) == len(manifest['layers'])
expected_image = manifest['config']['digest']
existing = subprocess.run(['docker', 'image', 'inspect', TAG, '--format', '{{.Id}}'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, universal_newlines=True)
if existing.returncode == 0 and existing.stdout.strip() == expected_image:
    print('official_base_image_already_verified=' + expected_image, flush=True)
    sys.exit(0)


def hash_file(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(CHUNK), b''):
            value.update(block)
    return 'sha256:' + value.hexdigest()


def download_part(layer, start, end):
    name = layer['digest'].split(':')[1]
    path = ROOT / (name + '.part-' + str(start))
    expected_size = end - start + 1
    if path.is_file() and path.stat().st_size == expected_size:
        return expected_size
    for attempt in range(3):
        try:
            request = urllib.request.Request(BASE + 'blobs/' + layer['digest'], headers={'Range': 'bytes=' + str(start) + '-' + str(end), 'Accept-Encoding': 'identity'})
            with urllib.request.urlopen(request, timeout=45) as response:
                assert response.status == 206
                assert response.headers['Content-Range'] == 'bytes ' + str(start) + '-' + str(end) + '/' + str(layer['size'])
                with path.open('wb') as output:
                    remaining = expected_size
                    while remaining:
                        data = response.read(min(128 * 1024, remaining))
                        assert data, 'Truncated official image range'
                        output.write(data)
                        remaining -= len(data)
                    assert not response.read(1)
            return expected_size
        except Exception:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


pending = []
for layer in manifest['layers']:
    path = ROOT / (layer['digest'].split(':')[1] + '.blob')
    if path.is_file() and path.stat().st_size == layer['size'] and hash_file(path) == layer['digest']:
        continue
    for start in range(0, layer['size'], CHUNK):
        pending.append((layer, start, min(start + CHUNK - 1, layer['size'] - 1)))
total = sum(end - start + 1 for _, start, end in pending)
print('parallel_official_download_bytes=' + str(total), flush=True)
completed = 0
with ThreadPoolExecutor(max_workers=16) as pool:
    futures = [pool.submit(download_part, *item) for item in pending]
    for future in as_completed(futures):
        completed += future.result()
        if completed // (10 * CHUNK) != (completed - future.result()) // (10 * CHUNK) or completed == total:
            print('parallel_download_progress_bytes=' + str(completed) + '/' + str(total), flush=True)

layers = []
for layer in manifest['layers']:
    name = layer['digest'].split(':')[1]
    path = ROOT / (name + '.blob')
    if not (path.is_file() and path.stat().st_size == layer['size'] and hash_file(path) == layer['digest']):
        with path.open('wb') as output:
            for start in range(0, layer['size'], CHUNK):
                with (ROOT / (name + '.part-' + str(start))).open('rb') as source:
                    for block in iter(lambda: source.read(CHUNK), b''):
                        output.write(block)
        assert path.stat().st_size == layer['size'] and hash_file(path) == layer['digest']
    layers.append(path)
    print('official_layer_sha256_verified=' + layer['digest'], flush=True)

compose_path = sys.argv[1]
assert compose_path.startswith('/opt/yfuse-calendar-renderer/releases/') and compose_path.endswith('/compose.yaml')
processes = subprocess.check_output(['ps', '-eo', 'pid,args'], universal_newlines=True)
for line in processes.splitlines():
    fields = line.strip().split(None, 1)
    if len(fields) == 2 and compose_path in fields[1] and 'build --progress plain calendar-renderer' in fields[1] and ('docker compose ' in fields[1] or 'docker-compose ' in fields[1]):
        os.kill(int(fields[0]), signal.SIGTERM)
        print('stopped_own_slow_image_build_pid=' + fields[0], flush=True)

archive_path = ROOT / 'official-image.tar'
with tarfile.open(str(archive_path), 'w') as archive:
    def add_bytes(name, data):
        item = tarfile.TarInfo(name)
        item.size = len(data)
        item.mode = 0o644
        archive.addfile(item, io.BytesIO(data))
    config_name = expected_image.split(':')[1] + '.json'
    add_bytes(config_name, config_bytes)
    names = []
    for layer, path in zip(manifest['layers'], layers):
        name = layer['digest'].split(':')[1] + '/layer.tar.gz'
        names.append(name)
        item = archive.gettarinfo(str(path), name)
        item.mode = 0o644
        with path.open('rb') as stream:
            archive.addfile(item, stream)
    add_bytes('manifest.json', json.dumps([{'Config': config_name, 'RepoTags': [TAG], 'Layers': names}]).encode())
print('loading_sha256_verified_official_image=true', flush=True)
subprocess.check_call(['docker', 'image', 'load', '--input', str(archive_path)])
loaded = json.loads(subprocess.check_output(['docker', 'image', 'inspect', TAG]))[0]
assert loaded['Id'] == expected_image
assert loaded['RootFS']['Layers'] == config['rootfs']['diff_ids']
evidence = {'source': TAG, 'indexDigest': INDEX, 'platformManifestDigest': descriptor['digest'], 'configDigest': expected_image, 'layerDigests': [layer['digest'] for layer in manifest['layers']], 'diffIdsVerifiedByDocker': config['rootfs']['diff_ids']}
(ROOT / 'verification.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('official_image_loaded_and_verified=' + expected_image, flush=True)
