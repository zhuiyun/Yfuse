"""Confirm a short remaining scene from the on-screen clock and captured video pixels."""


def confirm(samples, target, duration):
    for index in range(len(samples) - 2):
        first, middle, last = samples[index:index + 3]
        if first['elapsedMs'] > 3000 or abs(first['seconds'] - target) > 1.6:
            continue
        if any(s['durationSeconds'] != duration or s['seconds'] >= duration - 1 for s in (first, middle, last)):
            continue
        changes = []
        for before, after in ((first, middle), (middle, last)):
            elapsed = (after['elapsedMs'] - before['elapsedMs']) / 1000
            advance = after['seconds'] - before['seconds']
            difference = after.get('pictureChange', 0)
            if elapsed <= 0 or not 0 < advance <= elapsed + 1.2 or difference <= .8:
                break
            changes.append({'s': after['seconds'], 'pixelChange': difference})
        if len(changes) == 2:
            return {'firstPos': first['seconds'], 'laterPos': last['seconds'], 'durationSeconds': duration,
                    'secToFirst': first['elapsedMs'] / 1000, 'secToConfirm': last['elapsedMs'] / 1000,
                    'changes': changes}
    return None
