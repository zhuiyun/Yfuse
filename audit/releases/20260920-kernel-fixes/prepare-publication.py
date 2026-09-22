"""Stage the existing audited APK through the repository's transactional publisher."""
import hashlib
import json
import re
from pathlib import Path

audit = Path(__file__).resolve().parent
root = audit.parents[2]
stage = audit / 'publication'
stage.mkdir(exist_ok=True)
delivery = root / 'artifacts/releases/kernel-fixes-1.0.74-236'
verified = json.loads((delivery / 'verification.json').read_text(encoding='utf-8'))
apk = delivery / verified['apk']
assert verified['signatureVerified'] and verified['versionCode'] == 236
assert verified['versionName'] == '1.0.74'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == verified['sha256']
assert apk.stat().st_size == verified['bytes'] < 30_000_000
original = (root / 'scripts/publish-update.ps1').read_text(encoding='utf-8-sig')
publisher = original
replacements = {
    '$projectRoot = Split-Path -Parent $PSScriptRoot': "$projectRoot = 'D:/Demo/Yfuse'",
    '$apk = Join-Path $projectRoot "composeApp/build/outputs/apk/release/composeApp-release.apk"':
        f"$apk = '{apk.as_posix()}'",
    '$stage = Join-Path $projectRoot "build/update"': f"$stage = '{stage.as_posix()}'",
    'Write-Host "Retained the current and two previous APK versions"':
        'Write-Host "Historical APK files retained without pruning"',
}
for old, new in replacements.items():
    assert publisher.count(old) == 1, old
    publisher = publisher.replace(old, new)

# Keep historical downloads; this release only needs to activate the new immutable APK.
start = publisher.index('mapfile -t release_apks < <(')
end = publisher.index('\nrm -f -- \\\n', start)
publisher = publisher[:start] + '# Historical APK pruning intentionally omitted for this publication.\n' + publisher[end:]

# Refuse to replace manifests that changed while the APK was being uploaded.
anchor = 'cleanup_rollback\ninstall -m 0644 "$apk_tmp" "$apk_next"'
assert publisher.count(anchor) == 1
guard = '''[[ "$(sha256sum "$v2_manifest" | awk '{print $1}')" == "8a1b4b9cded78f318e43c5e2c76e8f004ab086fae56e9b9dddc65ef00ba06abb" ]] || { echo "Production v2 manifest changed since preflight" >&2; exit 4; }
[[ "$(sha256sum "$legacy_manifest" | awk '{print $1}')" == "f84ab2654ea38324327445da025c7423e58e7ab2ed41f5ec027ae86fbd9f657a" ]] || { echo "Production legacy manifest changed since preflight" >&2; exit 4; }
'''
publisher = publisher.replace(anchor, guard + '\n' + anchor)
(audit / 'publish-verified-update.ps1').write_text(publisher, encoding='utf-8')
notes = (delivery / 'release-notes.txt').read_text(encoding='utf-8-sig')
lines = notes.splitlines()
assert lines[0] == verified['versionName']
end = next((i for i, line in enumerate(lines[1:], 1) if re.fullmatch(r'\d+\.\d+\.\d+', line)), len(lines))
(stage / 'release-notes.txt').write_text('\n'.join(lines[:end]).strip() + '\n', encoding='utf-8')
(stage / 'plan.json').write_text(json.dumps({
    'versionName': verified['versionName'], 'versionCode': verified['versionCode'],
    'apkSha256': verified['sha256'], 'bytes': verified['bytes'],
    'sourceApk': apk.as_posix(),
    'target': 'https://47.112.219.60/yfuse/Yfuse-236-1.0.74.apk',
    'publisherSourceSha256': hashlib.sha256(original.encode()).hexdigest(),
    'publisherAdaptations': ['Use audited APK and isolated audit stage', 'Retain historical APKs',
                             'Reject concurrent manifest changes observed before activation'],
}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Prepared existing-APK publication: 1.0.74 (236); historical APK retention unchanged.')
