"""Validate partial and completed fixed-display SenPlayer windows without mixing transports."""
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import tempfile


def verify(folder, run, source):
    def read(name):
        return json.loads((folder / name).read_text(encoding='utf8'))

    assert f'app={source}' in (folder / 'provenance.txt').read_text().splitlines()
    hashes = {name: digest for digest, name in (v.split() for v in (folder / 'hashes.txt').read_text().splitlines())}
    assert (folder / 'device-source.txt').read_text().split()[0] == hashes['buffer-clock.mp4']
    receipt = read('download-receipt.json')
    assert len(hashes['app.apk']) == len(hashes['test.apk']) == 64 and receipt['digest'].startswith('sha256:')
    result = {'run': run, 'source': source, 'debugTargetSha256': hashes['app.apk'],
              'testApkSha256': hashes['test.apk'], 'fixtureSha256': hashes['buffer-clock.mp4'],
              'artifactDigest': receipt['digest'], 'transports': {}}
    if 'sen_buffer_viewport.patch' in hashes:
        trial = Path(__file__).resolve().parents[2] / 'qa-power-ui/sen_buffer_viewport.patch'
        assert 'variant=video-buffer-viewport-trial' in (folder / 'provenance.txt').read_text().splitlines()
        assert hashlib.sha256(trial.read_bytes().replace(b'\r\n', b'\n')).hexdigest() == hashes[trial.name]
        result['variant'] = 'video-buffer-viewport-trial'
        result['trialPatchSha256'] = hashes[trial.name]
    source_root = Path(__file__).resolve().parents[2]
    if 'SenPlayerBufferCostInstrumentedTest.kt' in hashes:
        provenance = dict(line.split('=', 1) for line in (folder / 'provenance.txt').read_text().splitlines())
        assert re.fullmatch('[a-f0-9]{40}', provenance['qa'])
        result['variant'] = provenance['variant']
        assert result['variant'] in ('scaled-measured-video-child', 'source-sized-before-first-surface', 'library-metadata-before-playback')
        test = 'composeApp/src/androidInstrumentedTest/kotlin/com/yfuse/feature/player/SenPlayerBufferCostInstrumentedTest.kt'
        driver = 'qa-power-ui/run_buffer_study.py'
        def committed(name):
            return subprocess.check_output(['git','-c','safe.directory='+source_root.as_posix(),
                                            'show',provenance['qa']+':'+name], cwd=source_root)
        test_bytes = committed(test)
        if result['variant'] == 'source-sized-before-first-surface':
            name = 'sen_initial_video_buffer.patch'
            patch = committed('qa-power-ui/'+name)
            assert hashlib.sha256(patch).hexdigest() == hashes[name]
            with tempfile.TemporaryDirectory() as tmp:
                q = Path(tmp, test)
                q.parent.mkdir(parents=True)
                q.write_bytes(test_bytes)
                subprocess.run(['git','apply','--no-index','--include='+test,'-'],cwd=tmp,input=patch,check=True)
                test_bytes = q.read_bytes().replace(b'\r\n', b'\n')
            result['trialPatchSha256'] = hashes[name]
        for name, contents in [(Path(test).name,test_bytes),(Path(driver).name,committed(driver))]:
            assert hashlib.sha256(contents).hexdigest() == hashes[name]
        result['harnessSha256'] = {name: hashes[name] for name in (Path(test).name, Path(driver).name)}
    outcomes = read('transport-outcomes.json')
    for transport in ('file', 'http'):
        summary = read(f'instrumentation-{transport}.json')
        path = folder / f'sen-buffer-{transport}.json'
        windows = json.loads(path.read_text()) if path.exists() else []
        attempt = outcomes[transport]
        assert len(windows) == attempt['recordedWindows'] <= 4
        headers = [(1280, 720), (640, 360), (640, 360), (1280, 720)]
        for index, row in enumerate(windows):
            assert row['transport'] == transport
            assert (row['videoBufferWidth'], row['videoBufferHeight']) == headers[index]
            if result.get('variant') in ('scaled-measured-video-child', 'source-sized-before-first-surface', 'library-metadata-before-playback'):
                g = row['geometry']
                assert read(f'buffer-{transport}-{index}-geometry.json') == g
                for key in ('window', 'parent', 'subtitleLayout', 'subtitleBuffer'):
                    assert (g[key+'Width'], g[key+'Height']) == (1280, 720)
                assert (g['videoBufferWidth'], g['videoBufferHeight']) == headers[index]
                layout = (1280,720) if result['variant'] in ('source-sized-before-first-surface', 'library-metadata-before-playback') else headers[index]
                assert (g['videoLayoutWidth'], g['videoLayoutHeight']) == layout
                assert g['videoScaleX'] == 1280/layout[0] and g['videoScaleY'] == 720/layout[1]
                assert g['parentBounds'] == g['videoDisplayBounds'] == g['subtitleDisplayBounds'] == '0 0 1280 720'
            else:
                assert [row['subtitleAndLayoutWidth'], row['subtitleAndLayoutHeight']] == [1280, 720]
            assert row['firstPictureSource'] in ('display', 'video-surface') and row['lastPictureSource'] in ('display', 'video-surface')
            if result.get('variant') == 'library-metadata-before-playback':
                assert row['libraryMetadata'] == (headers[index][0] == 640)
            if result.get('variant') in ('source-sized-before-first-surface', 'library-metadata-before-playback'):
                assert row['selectedBeforeCreation'] and row['sourceSizedFromCreation'] == (headers[index][0] == 640)
            else:
                assert 0 <= row['advancingBeforeResizeSecond'] < 96
            assert 0 <= row['firstPictureSecond'] < row['lastPictureSecond'] < 96
            assert row['elapsedMs'] >= 30000 and row['appCpuMs'] >= 0
            assert abs(row['percentOfOneCore'] - 100 * row['appCpuMs'] / row['elapsedMs']) < .00001
        completed = len(windows) == 4 and all(28 <= r['lastPictureSecond'] - r['firstPictureSecond'] <= 33 for r in windows)
        passed = (completed and summary['completed'] == 1 and summary['failed'] == summary['skipped'] == 0 and
                  summary['finished'] and (attempt['httpBytes'] > 0 if transport == 'http' else attempt['httpBytes'] == 0))
        coverage = attempt.get('pictureCoverage', {})
        if 'variant' in result:
            from sys import path
            path.insert(0, str(source_root / 'qa-power-ui'))
            from run_buffer_study import picture_coverage
            inspected = {f'{i}-{kind}': picture_coverage(folder / f'buffer-{transport}-{i}-{kind}.png')
                         for i in range(4) for kind in ('display', 'surface')}
            assert inspected == coverage
            passed = passed and all(v['fullField'] for v in inspected.values())
        assert passed == attempt['passed']
        files = {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in folder.glob(f'buffer-{transport}-*.png')}
        entry = {'passed': passed, 'windows': windows, 'sourceBytesSentByHttpServer': attempt['httpBytes'],
                 'instrumentation': summary, 'pictureSha256': files}
        if coverage:
            entry['pictureCoverage'] = coverage
        if passed:
            assert all(f'buffer-{transport}-{idx}-display.png' in files for idx in range(4))
            entry['medianPercentOneCore'] = {str(width): statistics.median(
                r['percentOfOneCore'] for r in windows if r['videoBufferWidth'] == width
            ) for width in (1280, 640)}
        result['transports'][transport] = entry
    return result
