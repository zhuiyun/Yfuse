"""Verify full-app source rejection wording without treating rejection as playback success."""
import json
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, 'qa-full-library')
import run
import retest_transport
import transport_audit

original_collect = run.collect_failure
classifications = []


def collect_failure(prefix, since):
    original_collect(prefix, since)
    raw = run.measure.adb('shell', 'cat /data/user/0/com.yfuse/no_backup/diagnostics/diagnostic-*.jsonl', check=False)
    evidence = []
    for line in raw.splitlines():
        try:
            event = json.loads(line)
            stamp = datetime.fromisoformat(event['timestamp'].replace('Z', '+00:00')).timestamp()
        except (ValueError, KeyError, TypeError):
            continue
        if stamp < since or event.get('event') != 'senplayer_playback_failed':
            continue
        attrs = event.get('attributes') or {}
        status = str(attrs.get('httpStatus', ''))
        kind = str(attrs.get('failureKind', ''))
        # Export allowlisted classification only; never locations, headers or exception messages.
        evidence.append({
            'httpStatus': int(status) if status in ('401', '403', '404', '410') else None,
            'kind': kind if kind in ('Authorization', 'Network', 'Decoder', 'Unknown') else 'other',
        })
    tree = run.ui.snapshot()
    visible = ' '.join(v for node in run.ui.nodes(tree) for v in run.ui.labels(node))
    classifications.append({
        'case': prefix,
        'sourceFailures': evidence,
        'showsHttp403': 'HTTP 403' in visible,
        'showsSourceDenied': '片源拒绝访问' in visible,
        'showsKernelUnavailable': '内核不可用' in visible,
        'playbackVerified': False,
    })
    Path('private-results/source-classification.json').write_text(json.dumps(classifications, indent=2))


if __name__ == '__main__':
    run.collect_failure = collect_failure
    run.controls = retest_transport.controls_with_startup_operations
    try:
        run.main()
    finally:
        payload = run.measure.load_envelope()
        transport_audit.inspect(payload['account'], payload['samples'])
