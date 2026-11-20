"""Look for a recorded delivery among the APKs in this checkout using Android package metadata."""
import argparse
import collections
import concurrent.futures
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('aapt', type=Path)
args = parser.parse_args()
paths = subprocess.check_output(['rg', '--files', '--hidden', '--no-ignore', '-g', '!**/.git/**', '-g', '*.apk', '.'], cwd=root).decode('utf-8').splitlines()


def info(name):
    try:
        result = subprocess.run([str(args.aapt), 'dump', 'badging', str(root / name)], capture_output=True, timeout=25)
        line = result.stdout.splitlines()[0].decode('utf-8', 'replace')
        package = re.search(r"^package: name='([^']+)' versionCode='(\d*)' versionName='([^']*)'", line)
        return (name, package.groups()) if package else (name, None)
    except (IndexError, OSError, subprocess.TimeoutExpired):
        return name, None


with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    reports = list(pool.map(info, paths))
counts = collections.Counter((v[1], v[2]) for _, v in reports if v and v[0] == 'com.yfuse' and v[1].isdigit())
targets = [p for p, v in reports if v and v[0] == 'com.yfuse' and (v[1] == '347' or v[2] == '1.9.5')]
unknown = [p for p, v in reports if v is None]
summary = {'scope': 'Workspace APKs, including ignored files; checked with Android SDK aapt dump badging.',
           'apkCount': len(reports), 'metadataRead': len(reports) - len(unknown), 'unreadable': unknown,
           'target': {'package': 'com.yfuse', 'versionName': '1.9.5', 'versionCode': 347},
           'matches': targets, 'yfuseNameAndCodeCounts': [{'code': int(k[0]), 'name': k[1], 'count': v}
                                                          for k, v in sorted(counts.items(), key=lambda kv: int(kv[0][0]))]}
(Path(__file__).resolve().parent / 'release-inventory.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(json.dumps({k: summary[k] for k in ('apkCount','metadataRead','unreadable','matches')}, ensure_ascii=False))
