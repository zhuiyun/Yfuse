"""Check source identity using the test user's normal Emby catalog, returning anonymous counts."""
import os
import argparse
import base64
import datetime
import getpass
import importlib.util
import inspect
import json
import shlex
import subprocess
from pathlib import Path


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--reference-round', default=19, type=int)
parser.add_argument('--round', default=24, type=int)
parser.add_argument('--verify-ranges', action='store_true')
args = parser.parse_args()
base = Path(__file__).resolve().parent
audit_root = base.parents[1] / 'power-ui-20261116'
reference = json.loads((audit_root / f'private-originals-{args.reference_round}-by-file.json').read_text(encoding='utf-8'))
expected = [{k: row[k] for k in ('label', 'kind', 'bytes', 'container', 'seconds')} for row in reference['media']]
source = (base / 'source_bridge_entry.py').read_text(encoding='utf-8').split("\nstage = 'config'", 1)[0]
spec = importlib.util.spec_from_file_location('session', base.parents[2] / 'qa-private-relay/serve_session.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
source += '\n' + inspect.getsource(module.catalog_identity) + '\n' + inspect.getsource(module.select_sources)
remote = source + '''
from collections import defaultdict
config = json.loads(sys.stdin.readline())
try:
    username, password = config.pop('credentials')
    session = call('/Users/AuthenticateByName', {'Username': username, 'Pw': password})
    TOKEN, USER = session.pop('AccessToken'), session['User']['Id']
    del username, password, session
    report = {'catalogRead': True, 'works': []}
    catalogs = {}
    for kind in ('Movie','Episode'):
        catalog = candidates(kind)
        catalogs[kind] = catalog
        ordered = choose(catalog, 20)
        matches = defaultdict(list)
        for item in catalog:
            info = (item.get('MediaSources') or [{}])[0]
            matches[int(info.get('Size') or 0)].append(item)
        for record in (r for r in config['expected'] if r['kind'] == kind):
            items = matches[record['bytes']]
            index = int(record['label'].split('-')[1])
            current = ordered[index] if index < len(ordered) else {}
            exact = []
            for item in items:
                info = (item.get('MediaSources') or [{}])[0]
                ticks = info.get('RunTimeTicks') or item.get('RunTimeTicks') or 0
                if int(ticks / 10000000) == record['seconds'] and (info.get('Container') or 'mkv') == record['container']:
                    exact.append(item)
            evidence = {'label': record['label'], 'kind': kind, 'sizeMatches': len(items),
                'sizeDurationContainerMatches': len(exact),
                'sameSelectedRecord': len(exact) == 1 and exact[0]['Id'] == current.get('Id')}
            if len(exact) == 1:
                media = exact[0]['MediaSources'][0]
                video = next((s for s in media.get('MediaStreams') or exact[0].get('MediaStreams', [])
                              if s.get('Type') == 'Video'), {})
                evidence.update(bytes=record['bytes'], seconds=record['seconds'], container=record['container'],
                                width=int(video.get('Width') or 0), height=int(video.get('Height') or 0),
                                bitDepth=video.get('BitDepth'), videoCodec=video.get('Codec'),
                                videoRange=video.get('VideoRange'), videoRangeType=video.get('VideoRangeType'))
            report['works'].append(evidence)
    if config.get('verifyRanges'):
        reference = {s['label']: s for s in config['expected']}
        selection = select_sources({'candidates': catalogs.__getitem__}, set(reference), reference)
        by_label = {s['label']: s for s in report['works']}
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for label, source_record, error in pool.map(resolve, selection):
                evidence = by_label[label]
                if error or not source_record:
                    evidence['ranges'] = {'resolved': False}
                    continue
                expected_record = reference[label]
                same = (source_record['type'], source_record['size'], source_record['seconds'], source_record['container']) == (
                    expected_record['kind'], expected_record['bytes'], expected_record['seconds'], expected_record['container'])
                evidence['ranges'] = {'resolved': True, 'sameFileMetadata': same,
                    'codes': source_record['rangeCodes'], 'verifiedPositions': sorted(source_record['rangeHashes'])}
        report['rangeBytesPerPosition'] = 1024
finally:
    if TOKEN:
        req = urllib.request.Request(ROOT + '/Sessions/Logout', data=b'{}', headers={
            'X-Emby-Authorization': AUTH, 'X-Emby-Token': TOKEN, 'Content-Type': 'application/json'})
        with API_HTTP.open(req, timeout=15) as response:
            if response.status not in (200, 204):
                raise RuntimeError('Catalog session logout failed')
        TOKEN = None
        report['loggedOut'] = True
print(json.dumps(report))
'''

credentials = json.loads(getpass.getpass('Private Emby input: '))
assert isinstance(credentials, list) and len(credentials) == 2
command = "exec(__import__('base64').b64decode(" + repr(base64.b64encode(remote.encode()).decode()) + '))'
checked = subprocess.run(['ssh', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=8', '--', os.environ['YFUSE_QA_SSH_TARGET'],
                          'python3', '-c', shlex.quote(command)], input=json.dumps({
                              'credentials': credentials, 'expected': expected, 'verifyRanges': args.verify_ranges}),
                         text=True, capture_output=True, timeout=1200 if args.verify_ranges else 180)
del credentials
if checked.returncode:
    raise SystemExit('Authenticated catalog verification did not complete. No media relay was opened.')
report = json.loads(checked.stdout)
assert report.get('loggedOut') and len(report['works']) == len(expected)
report.update(checkedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),referenceRound=args.reference_round)
prefix = 'private-origin' if args.verify_ranges else 'private-catalog'
(audit_root / f'{prefix}-{args.round}.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'catalogRowsCompared': len(report['works']),
                  'sameSelection': sum(row['sameSelectedRecord'] for row in report['works']),
                  'matchingOriginRanges': sum(len(row.get('ranges', {}).get('verifiedPositions', []))
                                              for row in report['works'] if row.get('ranges', {}).get('sameFileMetadata')),
                  'uniqueSizeDurationContainer': sum(row['sizeDurationContainerMatches'] == 1 for row in report['works']),
                  'loggedOut': report['loggedOut']}))
