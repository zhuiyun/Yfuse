"""Server-side approved deployment. No credentials or database rows leave this host."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sqlite3
import subprocess
import tarfile
import time
import urllib.error
import urllib.request

SHA = 'd3c58b952cb4361d4e4014f73756b16357b1b863'
DIGEST = 'ee2b6793daf33bb51ecea1ad71a3e0275f4daa07829049783420db77c992a057'
SERVICE = 'yfuse-update.service'
ROOT = Path('/opt/yfuse-watch')
CURRENT = ROOT/'current'
RELEASE = ROOT/'releases/20261117-d3c58b952-reviewed-retry2'
ARCHIVE = Path('/var/tmp/yfuse-backend-20261117-d3c58b952.tar.gz')
BACKUP = Path('/var/backups/yfuse/20261117-d3c58b952-reviewed-retry2')


def command(args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT).decode().strip()


def fetch(path, port, public=False):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        base = 'https://47.112.219.60' if public else 'http://127.0.0.1:'+str(port)
        with opener.open(base+path, timeout=3) as response:
            return response.status, response.read(100000)
    except urllib.error.HTTPError as error:
        return error.code, error.read(100000)


def backup_databases(databases, phase):
    folder = BACKUP/phase
    folder.mkdir(mode=0o700)
    entries = []
    for path in databases:
        if not path.exists():
            entries.append({'name': path.name, 'existed': False})
            continue
        destination = folder/path.name
        command(['sqlite3', str(path), '.timeout 5000', ".backup '"+str(destination)+"'"])
        os.chmod(str(destination), 0o600)
        check = command(['sqlite3', str(destination), 'PRAGMA integrity_check;'])
        assert check == 'ok', 'Backup integrity check failed'
        entries.append({'name': path.name, 'existed': True, 'integrity': check,
                        'bytes': destination.stat().st_size,
                        'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()})
    return entries


def switch(target):
    link = ROOT/'current.reviewed-next'
    assert not link.exists() and not link.is_symlink(), 'Unexpected pending deployment link'
    os.symlink(str(target), str(link))
    os.replace(str(link), str(CURRENT))


def main():
    os.umask(0o077)
    assert os.geteuid() == 0
    assert command(['systemctl', 'is-active', SERVICE]) == 'active'
    old = CURRENT.resolve()
    assert str(old).startswith(str(ROOT/'releases')+'/') and (old/'bin/watchTogetherServer').is_file()
    assert not RELEASE.exists() and not BACKUP.exists(), 'Refuse to overwrite a previous deployment'
    assert hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() == DIGEST
    pid = command(['systemctl', 'show', SERVICE, '-p', 'MainPID', '--value'])
    values = (Path('/proc')/pid/'environ').read_bytes().split(bytes([0]))
    env = dict(value.split(b'=', 1) for value in values if b'=' in value)
    assert env.get(b'MIGRATION_RELAY_MASTER_KEY'), 'Required existing migration key is missing'
    port = int(env.get(b'PORT', b'8080'))
    databases = []
    for key, default in [('ACCOUNT_DB_PATH', 'account.db'), ('CALENDAR_DB_PATH', 'calendar.db'),
                         ('MIGRATION_RELAY_DB_PATH', 'migration-relay.db'),
                         ('WATCH_STATE_DB_PATH', 'watch-state.db'), ('QOE_DB_PATH', 'qoe.db')]:
        path = Path(env.get(key.encode(), ('/var/lib/yfuse/'+default).encode()).decode()).resolve()
        assert path.parent == Path('/var/lib/yfuse') and path.name == default
        databases.append(path)
    command(['sudo', '-n', '-u', 'yfuse', 'test', '-w', '/var/lib/yfuse'])
    before_status, before_body = fetch('/watch/version', port)
    assert before_status == 200
    before = json.loads(before_body.decode())
    assert fetch('/api/v1/account/sessions', port, public=True)[0] == 401
    RELEASE.mkdir(mode=0o755)
    with tarfile.open(str(ARCHIVE)) as archive:
        for member in archive.getmembers():
            parts = PurePosixPath(member.name).parts
            assert parts and parts[0] == 'watchTogetherServer' and '..' not in parts
            assert member.isfile() or member.isdir(), 'Special archive entries are forbidden'
            target = RELEASE.joinpath(*parts[1:])
            if member.isdir():
                target.mkdir(mode=0o755, parents=True, exist_ok=True)
            else:
                target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
                with archive.extractfile(member) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)
                os.chmod(str(target), 0o755 if 'bin' in parts else 0o644)
    for directory in [RELEASE] + [path for path in RELEASE.rglob('*') if path.is_dir()]:
        os.chmod(str(directory), 0o755)
    command(['sudo', '-n', '-u', 'yfuse', 'test', '-x', str(RELEASE/'bin/watchTogetherServer')])
    BACKUP.mkdir(mode=0o700, parents=True)
    receipt = {'source': SHA, 'archiveSha256': DIGEST, 'oldRelease': str(old),
               'newRelease': str(RELEASE), 'backupDirectory': str(BACKUP), 'beforeVersion': before,
               'onlineBackup': backup_databases(databases, 'online'), 'status': 'prepared'}
    (BACKUP/'receipt.json').write_text(json.dumps(receipt, indent=2))
    stopped_at = time.monotonic()
    try:
        command(['systemctl', 'stop', SERVICE])
        receipt['switchBackup'] = backup_databases(databases, 'switch')
        switch(RELEASE)
        command(['systemctl', 'start', SERVICE])
        for attempt in range(30):
            try:
                health_status, health = fetch('/health', port)
                version_status, body = fetch('/watch/version', port)
                version = json.loads(body.decode())
                if health_status == 200 and health == b'ok' and version_status == 200 and version.get('gitSha') == SHA:
                    break
            except (OSError, ValueError):
                pass
            time.sleep(.5)
        else:
            raise RuntimeError('New backend health/version verification failed')
        plain_status, plain_body = fetch('/api/v1/account/sessions', port)
        assert plain_status == 426 and json.loads(plain_body.decode())['error']['code'] == 'https_required'
        assert fetch('/api/v1/account/sessions', port, public=True)[0] == 401, 'Unauthenticated HTTPS session access must stay rejected'
        assert command(['systemctl', 'is-active', SERVICE]) == 'active'
        receipt.update(status='deployed', afterVersion=version, health='ok',
                       unauthenticatedSessionsStatus=401, plainHttpSessionsStatus=426,
                       maintenanceSeconds=round(time.monotonic()-stopped_at, 2))
    except Exception as error:
        command(['systemctl', 'stop', SERVICE])
        switch(old)
        command(['systemctl', 'start', SERVICE])
        # Migrations were checked as additive. Never overwrite database changes after
        # the new listener may have accepted users; preserve all files on rollback.
        receipt.update(status='rolled-back', errorType=type(error).__name__,
                       databasesRestored=False, rollbackActive=command(['systemctl', 'is-active', SERVICE]))
        raise
    finally:
        (BACKUP/'receipt.json').write_text(json.dumps(receipt, indent=2))
        print(json.dumps(receipt))


if __name__ == '__main__':
    main()
