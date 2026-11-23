from pathlib import Path
import datetime,json,runpy,sys
ROOT=Path(__file__).resolve().parent
RAW=ROOT.parent/'legacy-engine-20261113/power-ui-20261116'
SOURCE='94ec647fac5132b5f3ee92e1304b98f57084e078'
APK='03058f533a38020fec500575a2ced18bc913bcde09934c6306cb35aa8b9d8d6a'
def read(p):
    return json.loads(p.read_text('utf8'))
def verify(folder,run,control_name='natural-originals-control'):
    old=RAW/'original-volume-retry-36'/'natural-originals-pilot'
    raw=folder/control_name
    old_summary=read(old/'summary.json')
    new_summary=read(raw/'summary.json')
    assert old_summary['files']==new_summary['files'] and len(new_summary['files'])==4
    assert (old/'apk.sha256').read_text().split()[0]==(raw/'apk.sha256').read_text().split()[0]==APK
    assert (old/'apk.sha256').read_text().splitlines()[1]!=(raw/'apk.sha256').read_text().splitlines()[1]
    assert 'app='+SOURCE in (raw/'provenance.txt').read_text().splitlines()
    parsed=runpy.run_path(str(ROOT/'verify_original_volume.py'))['verify'](folder,run,SOURCE,APK)
    comparisons=[]
    for key in old_summary['files']:
        for core in ('YCore','SenPlayer'):
            n=f'{key}-{core}.json'
            first=read(old/n)
            second=read(raw/n) if (raw/n).is_file() else {}
            assert first['planned']==6 and first['core']==core and first['id']==key
            comparable=False
            if second:
                assert second['planned']==6 and second['core']==core and second['id']==key
                comparable=(first['targets']==second['targets'] and all(first['original'][k]==second['original'][k]
                            for k in ('sha256','bytes','publisherRanges')))
            before=first['touches'][0]
            after=next(iter(second.get('touches',[])),{})
            if after.get('confirmed'):
                assert after['index']==before['index']==0 and after['fraction']==before['fraction']
                assert after['downX']!=after['releaseX'] and after['rail'][0]<=after['downX']<after['rail'][2]
            comparisons.append({'id':key,'kind':first['kind'],'engine':core,'sha256':first['original']['sha256'],'bytes':first['original']['bytes'],
                        'sameSourceAndPlan':comparable,'sourceError':new_summary['sources'].get(key,{}).get('failure') or second.get('sessionError'),
                        'firstBefore':{k:before.get(k) for k in ('issued','confirmed','fraction','beforeSecond','targetSecond','reason')},
                        'firstAfter':{k:after.get(k) for k in ('issued','confirmed','fraction','beforeSecond','targetSecond','rail','downX','releaseX','reason')},
                        'verifiedBefore':first['confirmed'],'verifiedAfter':next((r['confirmed'] for r in parsed['files']['pilot'] if r['id']==key and r['core']==core),0)})
    assert len(comparisons)==8 and sum(not r['firstBefore']['confirmed'] for r in comparisons)==4
    fixed=sum(c['sameSourceAndPlan'] and not c['firstBefore']['confirmed'] and bool(c['firstAfter']['confirmed']) for c in comparisons)
    return {'run':run,'priorRun':44378835634,'source':SOURCE,'apkSha256':APK,'allOriginalHashesAndTargetFractionsIdentical':all(c['sameSourceAndPlan'] for c in comparisons),
            'oldDriverSha256':(old/'apk.sha256').read_text().splitlines()[1].split()[0],
            'newDriverSha256':(raw/'apk.sha256').read_text().splitlines()[1].split()[0],'openingAttempts':comparisons,
            'formerlyFailedFirstTouchesNowPassed':fixed,'totals':parsed['metrics']['pilot'],'unavailable':parsed['unavailable']}
