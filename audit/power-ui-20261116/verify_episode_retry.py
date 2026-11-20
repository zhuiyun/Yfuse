"""Cross-check the public episode replay, retaining unsuccessful first attempts."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EPISODE = 'blender-fb70d459-48d2-4db5-adba-813c84f9200a'


def read(path):
    return json.loads(path.read_text(encoding='utf8'))


def picture(row, seconds, fraction=None):
    assert 0 <= row['firstPos'] < row['laterPos'] < seconds
    assert 0 <= row['secToFirst'] <= row['secToConfirm']
    changes = row['changes']
    assert len(changes) >= 2 and all(c['pixelChange'] >= .8 for c in changes)
    assert changes[-1]['s'] == row['laterPos'] and len({x['s'] for x in changes}) >= 2
    if fraction is not None:
        assert row['fraction'] == fraction
        for pos, elapsed in [('firstPos', 'secToFirst'), ('laterPos', 'secToConfirm')]:
            assert fraction*seconds-12.01 <= row[pos] <= fraction*seconds+row[elapsed]+12.01


def verify(folder, run, source):
    result = dict(run=run, source=source, title='Caminandes 2: Gran Dillama', routes={})
    hashes = set()
    catalog=read(folder/'workflow-artifacts.json')
    assert catalog['run']==run and catalog['repo']=='zhuiyun/Yfuse'
    artifacts={r['id']:r for r in catalog['artifacts']}
    for mode in ('origin', 'loopback'):
        path = folder / ('original-startup-control-' + mode)
        if not (path/'metrics.json').is_file():
            continue
        receipt = read(path/'download-receipt.json')
        owner=artifacts[receipt['id']]
        assert owner['digest']==receipt['digest'] and owner['bytes']==receipt['expectedSize'] and owner['name']==path.name
        assert f'app={source}' in (path/'provenance.txt').read_text().splitlines()
        hashes.add((path/'apk.sha256').read_text().split()[0])
        rows = read(path/'metrics.json')['measurements']
        cache = read(path/'cache-open-pairs.json') if (path/'cache-open-pairs.json').is_file() else []
        checks = read(path/'independent-origin-ranges.json')
        pairs = []
        for row in checks:
            if row['exitCode'] == 0 and row.get('http_code') == 206 and row.get('returnedBytes') == row['length']:
                pairs.append([row['requestedProtocol'], row['start'], row['length'], row['bodySha256']])
        trials = []
        for index, observation in enumerate(cache):
            prefix = f'{EPISODE}-{mode}-{index}-'
            cases = [r for r in rows if r['case'].startswith(prefix)]
            def tagged(tag):
                return [r for r in cases if r['case'] == prefix+tag]
            cleared = index % 2 == 0
            assert observation['attempt'] == index+1 and observation['pair'] == index//2+1 and observation['cleared'] == cleared
            if cleared:
                assert observation['before'] == {'ranges': 0, 'mediaBytes': 0}
            fmts, opened, identities, done = (tagged(name) for name in ('format', 'opened', 'engine', 'complete'))
            assert len(fmts) <= 1 and len(opened) <= 1 and len(identities) <= 1 and len(done) <= 1
            confirmed = bool(done and done[0]['ok'])
            assert observation['confirmed'] == confirmed
            seeks, issued = tagged('seek'), tagged('issued')
            assert len(seeks) <= len(issued) <= 2
            if opened:
                assert fmts and fmts[0]['kind'].casefold() == 'episode' and fmts[0]['bytes'] > 0
                assert identities and identities[0]['active'] and identities[0]['senOriginalLibrary'] and identities[0]['senOutputEvent']
                picture(opened[0], fmts[0]['seconds'])
                for seek in seeks:
                    assert seek['fraction'] in (.63, .21)
                    picture(seek, fmts[0]['seconds'], seek['fraction'])
            if confirmed:
                assert opened and [r['fraction'] for r in seeks] == [r['fraction'] for r in issued] == [.63,.21]
            trials.append({'cache': observation, 'opened': bool(opened), 'bytes': fmts[0]['bytes'] if fmts else None,
                           'seconds': fmts[0]['seconds'] if fmts else None,
                           'startSeconds': opened[0]['secToConfirm'] if opened else None,
                           'seeksIssued': len(issued), 'seeksConfirmed': len(seeks),
                           'landings': [{'fraction':r['fraction'], 'firstPos':r['firstPos'],'laterPos':r['laterPos']} for r in seeks],
                           'hostRanges206':sum(bool(tagged(tag)) and tagged(tag)[0].get('status')==206 and
                                               tagged(tag)[0].get('exactRange') and tagged(tag)[0].get('returned')==1024
                                               for tag in ('start','middle','end'))})
        summary = next((r for r in rows if r['case']=='repeat-summary'), None)
        if summary:
            assert summary['mode']==mode and summary['plannedOpens']==12 and summary['sourceId']==EPISODE
            assert summary['passedOpens']==sum(r['cache']['confirmed'] for r in trials)
        route = dict(trials=trials,checkedRanges=pairs,archiveDigest=receipt['digest'],complete=bool(summary),
                     passedAttempts=sum(r['cache']['confirmed'] for r in trials),plannedAttempts=12)
        if mode=='loopback':
            files = [r for r in rows if r['case']=='original-control-file']
            assert len(files)==1 and len(files[0]['sha256'])==64
            route['downloadedOriginalSha256'] = files[0]['sha256']
            route['downloadedOriginalBytes'] = files[0]['bytes']
            assert all(r['bytes'] in (None,files[0]['bytes']) for r in trials)
        result['routes'][mode]=route
    assert len(hashes) <= 1
    result['apkSha256'] = next(iter(hashes), None)
    if len(result['routes'])==2:
        left,right=(result['routes'][k] for k in ('origin','loopback'))
        a={tuple(s[:3]):s[-1] for s in left['checkedRanges']}
        b={tuple(s[:3]):s[-1] for s in right['checkedRanges']}
        common = a.keys() & b.keys()
        assert all(a[k]==b[k] for k in common)
        result['matchedIndependentRanges']=len(common)
    result['passed']=len(result['routes'])==2 and all(r['complete'] and r['passedAttempts']==12 for r in result['routes'].values())
    return result


if __name__ == '__main__':
    directory=ROOT.parent/'legacy-engine-20261113/power-ui-20261116/episode-reader-check-35'
    result=verify(directory,44371441364,'ba340b12e150917e108403ab9b95a966abe96b62')
    if result['routes']:
        target=ROOT/'episode-recovery-35.json'
        target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
        print([(route,value['passedAttempts']) for route,value in result['routes'].items()])
