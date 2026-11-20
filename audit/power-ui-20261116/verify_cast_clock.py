"""Re-read the receiver's visible timecode and independently check each audio-clock comparison."""
import hashlib
import json
from pathlib import Path

from PIL import Image


def read(path):
    return json.loads(path.read_text('utf8'))


def decoded_second(path):
    with Image.open(path) as image:
        pixels = image.convert('RGB')
    assert pixels.size == (160, 90)
    rows = []
    for y in (8, 10):
        assert min(pixels.getpixel((4, y))) >= 180 and max(pixels.getpixel((12, y))) <= 65
        value = 0
        for bit in range(7):
            rgb = pixels.getpixel((20 + 8 * bit, y))
            assert min(rgb) >= 180 or max(rgb) <= 65
            value |= int(min(rgb) >= 180) << bit
        rows.append(value)
    assert rows[0] == rows[1] and rows[0] < 96
    return rows[0]


def verify(folder, run, source, apk):
    owners = read(folder / 'workflow-artifacts.json')
    assert owners['run'] == run and owners['repo'] == 'zhuiyun/Yfuse'
    records = []
    fixtures = set()
    for core in ('YCore', 'SenPlayer'):
        for clock in ('paced', 'unpaced'):
            path = folder / f'dlna-receiver-{core}-{clock}'
            receipt = read(path / 'download-receipt.json')
            assert any(a['id'] == receipt['id'] and a['digest'] == receipt['digest'] for a in owners['artifacts'])
            assert f'app={source}' in (path / 'provenance.txt').read_text().splitlines()
            assert (path / 'apk.sha256').read_text().split() == [apk, 'composeApp-release.apk']
            result = read(path / 'outcome.json')
            assert (result['core'], result['audioClock']) == (core, clock)
            identity = result['localPlayback']['identity']
            assert identity['engine'] == core and identity['active']
            fixtures.add(result['fixtureSha256'])
            for name in ('discovery', 'connected', 'stopped'):
                if name in result:
                    screen = result[name]
                    assert hashlib.sha256((path/(screen['name']+'.png')).read_bytes()).hexdigest() == screen['sha256']
            frames = {}
            for key, frame in [('remoteStart','start'),('forwardSeek','forward'),('backwardSeek','backward'),('resumed','resumed')]:
                if key in result:
                    shot = result[key]
                    assert 0 <= shot['firstSecond'] < shot['second'] == decoded_second(path / (frame+'-receiver.png'))
                    assert shot['firstFrame'] < shot['frame']
                    frames[key] = [shot['firstSecond'], shot['second']]
            paused = result.get('receiverPause', {}).get('second')
            phone = result.get('phonePause', {}).get('positionAfter')
            resumed = result.get('resumed')
            continuity = None
            if resumed and paused is not None and phone is not None:
                first = resumed['firstSecond']
                continuity = {'pausedClockAgreesWithPicture': abs(paused-phone) <= 2,
                              'resumedAtPausedPicture': paused-1 <= first <= paused+2+resumed['firstObservedAfterSeconds'],
                              'resumedFramesAdvanceInOrder': 0 < resumed['second']-first <= resumed['frame']-resumed['firstFrame']+1}
                if 'continuity' in result:
                    assert continuity == result['continuity']
            if result['passed']:
                assert continuity and all(continuity.values()) and result['bytesDeliveredToReceiver'] > 0
                assert result['localReturn']['laterPos'] > result['localReturn']['firstPos']
            events = read(path/'cast-events.json')
            assert any(e['event'] == 'dlna_load_requested' for e in events)
            handoff = [e['attributes'].get('attempt') for e in events if e['event'] == 'dlna_start_seek']
            records.append({'core':core,'audioClock':clock,'passed':result['passed'],'receiverFrames':frames,
                            'receiverPaused':paused,'phonePaused':phone,'checks':continuity,'handoffSeeks':handoff,
                            'localPlaybackReturned':'localReturn' in result,'artifactDigest':receipt['digest']})
    assert len(fixtures) == 1
    return {'run':run,'source':source,'apkSha256':apk,'fixtureSha256':next(iter(fixtures)),'results':records}
