"""Summarize original-file stress observations after checking every gesture and its APK."""
import base64
import csv
import hashlib
import json
from collections import Counter
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = '94ec647fac5132b5f3ee92e1304b98f57084e078'
EXPECTED_APK = '03058f533a38020fec500575a2ced18bc913bcde09934c6306cb35aa8b9d8d6a'
CORES = ('YCore', 'SenPlayer')


def load(path):
    return json.loads(path.read_text(encoding='utf8'))


def verify_case(item, spec, planned, local_hash):
    assert item['id'] == spec['id'] and item['kind'] == spec['kind'] and item['core'] in CORES
    original = item['original']
    size, duration = original['bytes'], original['duration']
    assert original['sha256'] == local_hash and len(local_hash) == 64 and duration > 0
    assert [r['offset'] for r in original['publisherRanges']] == [0, size//2, size-4096]
    assert all(r['bytes'] == 4096 and len(r['sha256']) == 64 for r in original['publisherRanges'])
    assert item['planned'] == len(item['targets']) == planned >= len(item['touches'])
    for session in item['sessions']:
        op = session['opened']
        assert op['identity']['active'] and op['identity']['engine'] == item['core']
        assert op['movingPicture']['laterPos'] > op['movingPicture']['firstPos'] and len(op['movingPicture']['changes']) >= 2
    classes = Counter()
    unresolved = []
    corroborated = []
    has_followup_protocol = "sourceCorroboratedGestures" in item
    timeline = item.get('referenceTimeline')
    if timeline is not None:
        assert timeline['mode'] == 'native-presentation-timestamps' and timeline['frames'] > 1
        assert timeline['firstSecond'] <= timeline['lastSecond'] and 0 <= timeline['lastSecond'] <= duration + 1
        assert len(bytes.fromhex(timeline['timestampsSha256'])) == 32
    elif has_followup_protocol:
        assert item['referenceFramesPerSecond'] == 6
    for index, row in enumerate(item['touches']):
        assert row['index'] == index and row['fraction'] == item['targets'][index]
        if row.get('output'):
            proof = row['output']
            classes[proof['sourcePicture']['classification']] += 1
        if timeline is not None:
            pictures = [row.get('output', {}).get('sourcePicture'), row.get('secondSourcePicture')]
            preserve = False
            for picture in filter(None, pictures):
                preserve = preserve or picture['classification'] not in ('distinct-match', 'consistent-repeated-picture')
                if preserve:
                    sample = base64.b64decode(picture['luma96x54'], validate=True)
                    inflater = zlib.decompressobj()
                    pixels = inflater.decompress(sample, 96 * 54 + 1)
                    assert inflater.eof and not inflater.unused_data and len(pixels) == 96 * 54
                    assert hashlib.sha256(pixels).hexdigest() == picture['lumaSha256']
            if row.get('secondSourcePicture'):
                first_time = pictures[0]['clockObservedMonotonic']
                second = pictures[1]
                start, end = second['beforeClockMonotonic'], second['afterClockMonotonic']
                assert first_time <= start <= end <= first_time + 7
                assert abs((start + end) / 2 - first_time - second['secondsAfterFirstCheck']) <= .00051
        if not row['confirmed']:
            continue
        assert row['issued'] and row['beforeSecond'] is not None
        left, _, right, _ = row['rail']
        width = right-left-1
        expected_x = left + (int(width*row['fraction']+.5) if 'downX' in row else round(width*row['fraction']))
        assert row['releaseX']==expected_x
        if 'downX' in row:
            assert left <= row['downX'] < right and row['downX'] != row['releaseX']
        target = duration*(row['releaseX']-left)/width
        assert abs(target-row['targetSecond']) < .0001
        tolerance = max(2.25,duration/width+1.5)
        assert row['toleranceSeconds'] == tolerance and abs(row['beforeSecond']-target) > tolerance+2
        proof = row['output']
        assert 0 <= proof['firstPos'] < proof['laterPos'] < duration-1 and len(proof['changes']) >= 2
        assert proof['changes'][-1]['s'] == proof['laterPos'] and all(z['pixelChange'] >= .8 for z in proof['changes'])
        for field, wall in [('firstPos','secToFirst'),('laterPos','secToConfirm')]:
            assert target-tolerance-.01 <= proof[field] <= target+proof[wall]+tolerance+.01
        assert proof['sourcePicture']['playerSecond'] == proof['laterPos']
        matched = ('distinct-match','consistent-repeated-picture')
        first_class = proof['sourcePicture']['classification']
        if has_followup_protocol:
            second = row.get('secondSourcePicture')
            if second is not None:
                before,after=second['clockBeforeCapture'],second['clockAfterCapture']
                assert proof['laterPos'] < before <= after <= before + 2 and 0 <= second['secondsAfterFirstCheck'] <= 7
                assert second['playerSecond']==(before+after)/2 and second['playerSecond']<duration
                assert second['playerSecond']<=proof['laterPos']+second['secondsAfterFirstCheck']+tolerance
                assert first_class not in matched and second['classification']!='different-scene'
            supported=first_class in matched or (second is not None and second['classification'] in matched)
            assert row['sourceCorroborated']==supported
            if first_class=='different-scene' and not supported:unresolved.append(index)
            if supported:corroborated.append(index)
        elif first_class=='different-scene':
            second=row.get('secondSourcePicture',{})
            if (second.get('classification') not in matched or second.get('playerSecond',-1)<=proof['laterPos']
                    or ('downX' in row and ('secondsAfterFirstCheck' not in second or second['playerSecond']>=duration
                        or second['playerSecond']>proof['laterPos']+second['secondsAfterFirstCheck']+tolerance))):
                unresolved.append(index)
    reported = sum(row['confirmed'] for row in item['touches'])
    consistent = classes['distinct-match'] + classes['consistent-repeated-picture']
    assert classes == Counter(item['pictureClasses']) and reported == item['confirmed']
    if has_followup_protocol:
        assert corroborated==item['sourceCorroboratedGestures']
        consistent=len(corroborated)
    assert item['passed'] == (reported==planned and consistent>=min(planned,2))
    confirmed=reported-len(unresolved)
    return {'id':item['id'], 'kind':item['kind'], 'core':item['core'], 'bytes':size, 'durationSeconds':duration,
            'container':original['container'], 'videoCodec':original['video']['codec_name'], 'sha256':local_hash,
            'planned':planned, 'issued':sum(row['issued'] for row in item['touches']), 'confirmed':confirmed,
            'openedSessions':len(item['sessions']), 'sourceCorroboratedGestures':consistent, 'classes':dict(classes), 'passed':item['passed'] and not unresolved,
            'preTouchControlFailures':sum('firstDriverOutcomeBeforeDown' in row for row in item['touches']),
            'referenceTimeline':timeline,
            'reportedPassed':item['passed'],'reportedConfirmed':reported,'unresolvedSceneConflicts':unresolved,
            'firstFailures':[{k:row.get(k) for k in ('index','fraction','issued','reason')} for row in item['touches'] if not row['confirmed']][:4]}


def verify(folder, run, source=SOURCE, expected_apk=EXPECTED_APK):
    catalog = [entry for file in ('qa-format-originals/sources.json','qa-titles/catalog.json')
               for entry in load(ROOT.parents[1]/file)]
    by_id = {v['id']:v for v in catalog}
    owners=load(folder/'workflow-artifacts.json')
    assert owners['repo']=='zhuiyun/Yfuse' and owners['run']==run
    owned={v['id']:v for v in owners['artifacts']}
    stages={'pilot':[],'volume':[]}
    hashes=set()
    unavailable=[]
    for path in sorted(folder.glob('natural-originals-*/summary.json')):
        base=path.parent
        receipt=load(base/'download-receipt.json')
        owner=owned[receipt['id']]
        assert receipt['digest']==owner['digest'] and receipt['name']==owner['name']==base.name
        assert f'app={source}' in (base/'provenance.txt').read_text().splitlines()
        measured_apk=(base/'apk.sha256').read_text().split()[0]
        assert measured_apk==expected_apk
        hashes.add(measured_apk)
        summary=load(path)
        stage=summary['stage']
        count=summary['plannedPerFilePerCore']
        if stage=='volume':
            number=int(base.name.rsplit('-',1)[-1])
            assert summary['files']==[v['id'] for v in catalog[number::22]] and count==120
        else:
            assert stage=='pilot' and count==6 and len(set(summary['files']))==4
        for label in summary['files']:
            entry=summary['sources'].get(label,{})
            for core in CORES:
                evidence=base/f'{label}-{core}.json'
                if not evidence.is_file() or not entry.get('sha256'):
                    unavailable.append({'stage':stage,'core':core,'id':label,'sourceError':entry.get('failure')})
                    continue
                case=load(evidence)
                v=verify_case(case,by_id[label],count,entry['sha256'])
                photographed=set()
                for row in case['touches']:
                    pictures=[row.get('output',{}).get('sourcePicture',{}),row.get('secondSourcePicture',{})]
                    for seq,picture in enumerate(pictures):
                        name=picture.get('observedImage')
                        if name is None:continue
                        assert name==f"{label}-{core}-{row['index']}-source-{seq}.png"
                        assert hashlib.sha256((base/name).read_bytes()).hexdigest()==picture['imageSha256']
                        photographed.add(row['index'])
                if 'sourceImageExamplesPerOutcome' in case:
                    assert case['sourceImageExamplesPerOutcome'] == 2
                    assert all(count <= 2 for count in Counter(case['touches'][i]['confirmed'] for i in photographed).values())
                else:
                    assert len(photographed)<=2
                assert v['core']==core
                if core in entry.get('results',{}):
                    assert entry['results'][core]==v['reportedPassed']
                stages[stage].append(v)
    assert len(hashes)<=1
    metrics={}
    for stage,rows in stages.items():
        metrics[stage]={}
        for core in CORES:
            cases=[r for r in rows if r['core']==core]
            assert len(cases)==len({r['id'] for r in cases})
            expected=4 if stage=='pilot' else len(catalog)
            expected_gestures=expected*(6 if stage=='pilot' else 120)
            classes=Counter()
            for r in cases:
                classes.update(r['classes'])
            metrics[stage][core]={'filesMeasured':len(cases),'expectedFiles':expected,'filesPassed':sum(r['passed'] for r in cases),
                                  'uniqueOriginalHashes':len({r['sha256'] for r in cases}),'expectedGestures':expected_gestures,
                                  'issued':sum(r['issued'] for r in cases),'verified':sum(r['confirmed'] for r in cases),
                                  'sourcePictureClasses':dict(classes),'sourceCorroboratedGestures':sum(r['sourceCorroboratedGestures'] for r in cases)}
    return {'run':owners['run'],'source':source,'apkSha256':next(iter(hashes),None),'metrics':metrics,'files':stages,'unavailable':unavailable}


if __name__=='__main__':
    base=ROOT.parent/'legacy-engine-20261113/power-ui-20261116/original-volume-retry-36'
    record=verify(base,44378835634)
    (ROOT/'original-volume-36.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    fields=['stage','core','kind','id','bytes','durationSeconds','container','videoCodec','sha256','openedSessions','planned','issued','confirmed','passed']
    with (ROOT/'original-volume-files-36.csv').open('w',encoding='utf-8-sig',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=fields,extrasaction='ignore')
        writer.writeheader()
        for stage,rows in record['files'].items():
            writer.writerows({'stage':stage,**row} for row in rows)
    print(record['metrics'])
