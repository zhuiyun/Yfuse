"""Verify cloud ARM instrumentation, its old-APK controls, and the same run's host test receipts."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
RAW = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def case_counts(text):
    results = Counter()
    names = {}
    fields = {}
    for line in text.splitlines():
        field = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test)=(.+)', line)
        if field:
            fields[field[1]] = field[2]
        status = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if status:
            if fields.keys() >= {'class', 'test'}:
                case = fields['class'] + '#' + fields['test']
                code = int(status[1])
                results[code] += 1
                if code != 1:
                    assert case not in names, case
                    names[case] = code
            fields = {}
    return results, names


def device_result(directory, stem, expected, required, previous):
    transcript = (directory / (stem + '.txt')).read_bytes()
    text = transcript.decode('utf-8')
    outcome = read(directory / (stem + '.json'))
    counts, cases = case_counts(text)
    assert counts[1] == len(cases) == expected and not counts[-3] and not counts[-4]
    complete, failed = counts[0], counts[-1] + counts[-2]
    assert (complete, failed) == ((0, expected) if previous else (expected, 0))
    assert (complete, failed, 0, not previous) == tuple(outcome[k] for k in ('completed', 'failed', 'skipped', 'finished'))
    assert all(marker in text for marker in required), stem
    if not previous:
        assert re.search(r'OK \(' + str(expected) + r' tests?\)', text)
    return {'transcript': stem + '.txt', 'transcriptSha256': hashlib.sha256(transcript).hexdigest(),
            'uniqueCases': list(cases), 'passed': complete, 'failedAsExpected': failed}


def verify(number):
    state_file = ROOT / f'iteration-{number}-verification.json'
    state = read(state_file)
    directory = RAW / f'native-contracts-{number}'
    owners = read(directory / 'workflow-artifacts.json')
    run = read(directory / 'run.json')
    assert owners['run'] == run['id'] == state['qualificationRun']
    assert run['conclusion'] == 'success' and run['head_sha'] == state['qaHead']
    assert run['full_name'] == owners['repo'] == 'zhuiyun/Yfuse'
    assert run['path'] == '.github/workflows/qa-integrated-av-repair.yml'
    artifact = next(v for v in owners['artifacts'] if v['name'] == 'native-video-repair-contracts')
    directory = directory / artifact['name']
    receipt = read(directory / 'download-receipt.json')
    assert receipt['id'] == artifact['id'] and receipt['digest'] == artifact['digest']
    assert artifact['workflowHead'] == run['head_sha']
    local = ROOT / f'qualification-{number}'
    host, repeat = (read(local / (n + '.json')) for n in ('repair-test-reports', 'probe-cancellation-repeats'))
    for report in (host, repeat):
        assert report['run'] == run['id']
        assert all(report['totals'][v] == 0 for v in ('errors','failures','skipped'))
    assert host['totals']['tests'] == host['uniqueMethods'] and repeat['totals']['tests'] == 320
    positive = {
        'contracts': (17, ['test=' + name for name in (
            'latePreviewReadsOnlyCommittedSenPlayerCacheSpans', 'decodeUsesPlaybackBytesAndRejectsMissingDataAndOldEpisode',
            'long_movie_clock_never_moves_the_held_seek_rail', 'enhanced_transport_retry_keeps_the_assigned_route_and_renders_again',
            'bodyTimeoutResumesAtTheFirstMissingByteAndCachesOnlyVerifiedBytes', 'playback_problem_captions_are_readable_on_bright_and_dark_pictures')]),
        'http2-recovery': (1, ['test=stalledHttp2BodyResumesOnIndependentTlsConnectionWithTheSamePinAndMissingRange']),
    }
    negative = {'http2-recovery-negative': ['SocketTimeoutException', 'Http2Stream.takeHeaders', 'PlaybackResponseSource.resume'],
                'body-timeout-negative': ['A body timeout must reopen the missing suffix'],
                'cast-discovery-negative': ['ModuleUnavailableException'], 'geometry-negative': ['Clock changed the held seek rail'],
                'caption-negative': ['Caption fade is too abrupt'], 'diagnostic-contrast-negative': ['Playback diagnostic captions below 4.5:1']}
    if number >= 49:
        for stem, name in [('range-startup', 'continuousDemuxReadsUseASingleHttpRangePerFollowingBlockWithoutAnEntityTag'),
                           ('range-foreground', 'continuousDemuxReadsUseFullForegroundRangeWhenNoSpeculativeBlockFits')]:
            positive[stem] = (1, ['test=' + name])
            negative[stem + '-negative'] = ['test=' + name, 'Sequential demux must use one HTTP exchange for the next block']
    passes = [device_result(directory, name, count, criteria, False) for name, (count, criteria) in positive.items()]
    controls = [device_result(directory, name, 1, criteria, True) for name, criteria in negative.items()]
    minima = {}
    for label in ('current', 'prior'):
        color = read(directory / (label + '-problem-contrast') / 'measurements.json')
        assert len(color) == 45
        minima[label] = min(min(v['contrast'], v['settledContrast']) for v in color)
    assert minima['current'] >= 4.5 > minima['prior']
    evidence = {'round': number, 'run': run['id'], 'source': state['source'], 'qaHead': run['head_sha'],
                'armArtifactId': artifact['id'], 'armArtifactSha256': artifact['digest'],
                'hostUniqueTests': host['uniqueMethods'], 'hostRepeatedTests': repeat['totals']['tests'],
                'armPositiveCases': sum(v['passed'] for v in passes), 'oldApkNegativeCases': len(controls),
                'armTests': passes, 'oldApkControls': controls, 'lowestDiagnosticContrast': minima}
    output = ROOT / f'arm-qualification-{number}.json'
    output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    state.update(qualificationPassed=True, pendingQualification=False, hostUniquePassed=host['uniqueMethods'],
                 repeatedCases=repeat['totals']['tests'], positiveDeviceTests=evidence['armPositiveCases'],
                 negativeControls=evidence['oldApkNegativeCases'], nativeContractEvidence=output.name)
    state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(number, evidence['hostUniqueTests'], evidence['hostRepeatedTests'], evidence['armPositiveCases'], len(controls))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int, choices=(47, 49, 50))
    verify(parser.parse_args().round)
