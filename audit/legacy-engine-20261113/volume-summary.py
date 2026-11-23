"""Collect recorded device gestures, classifying incomplete shards separately from completed runs."""
import collections
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parent
inputs = sorted((root / 'volume').glob('volume-*.json'))
shards, observations, shards_partial, opens, missing_opens = [], [], [], set(), set()
reasons = collections.Counter()
groups = collections.defaultdict(list)
sizes = []
for file in inputs:
    raw = json.loads(file.read_text(encoding='utf-8'))
    group = int(raw['shard'])
    assert group not in shards and group not in shards_partial
    (shards if 'verified' in raw and 'issued' in raw else shards_partial).append(group)
    for clip in raw['files']:
        assert clip['name'].startswith('clip-')
        if clip.get('open') == 'passed':
            opens.add(clip['name'])
        elif clip.get('open'):
            missing_opens.add(clip['name'])
        sizes.append(clip['size'])
        rows = clip.get('rows', [])
        observations.extend(rows)
        key = clip['codec'].upper() + '/' + clip['name'].rsplit('.', 1)[1].upper()
        groups[key].extend(rows)
        for row in rows:
            if not row.get('ok'):
                reasons[row.get('reason') or 'unknown'] += 1


def metrics(rows):
    times = sorted(r['ms'] for r in rows if r.get('ok') and 'ms' in r)
    def quantile(q):
        return times[math.ceil(q * len(times)) - 1] if times else None
    return {'observations': len(rows), 'issued': sum(bool(r.get('issued')) for r in rows),
            'verified': sum(bool(r.get('ok')) for r in rows), 'p50VerifiedMs': quantile(.5),
            'p95VerifiedMs': quantile(.95)}


report = {'runs': [43360363368, 43364313750], 'shardsWithFinalTotals': sorted(shards),
          'partialShards': sorted(shards_partial), 'openedFixtures': len(opens),
          'openNotConfirmed': sorted(missing_opens), 'all': metrics(observations),
          'byCodecContainer': {k: metrics(v) for k, v in sorted(groups.items())},
          'nonPassReasons': dict(reasons), 'sizeRangeBytes': [min(sizes), max(sizes)] if sizes else []}
(root / 'volume-summary.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: report[k] for k in ('shardsWithFinalTotals', 'partialShards', 'all', 'nonPassReasons', 'openedFixtures')}))
