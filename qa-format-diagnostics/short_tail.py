"""Confirm a short remaining scene from the on-screen clock and captured video pixels."""


def confirm(samples, target, duration):
    for index in range(len(samples) - 2):
        first = samples[index]
        if (first['elapsedMs'] > 3000 or abs(first['seconds'] - target) > 1.6
                or first['durationSeconds'] != duration or first['seconds'] >= duration - 1):
            continue
        changes = []
        for before, after in zip(samples[index:], samples[index + 1:]):
            elapsed = (after['elapsedMs'] - before['elapsedMs']) / 1000
            advance = after['seconds'] - before['seconds']
            since_first = (after['elapsedMs'] - first['elapsedMs']) / 1000
            difference = after.get('pictureChange', 0)
            if (after['durationSeconds'] != duration or after['seconds'] >= duration - 1 or elapsed <= 0
                    or not 0 < advance <= elapsed + 1.2 or since_first > 5.5
                    or after['seconds'] - first['seconds'] > since_first + 1.2):
                break
            if difference > .8:
                changes.append({'s': after['seconds'], 'pixelChange': difference})
            if len(changes) == 2:
                return {'firstPos': first['seconds'], 'laterPos': after['seconds'], 'durationSeconds': duration,
                        'secToFirst': first['elapsedMs'] / 1000, 'secToConfirm': after['elapsedMs'] / 1000,
                        'changes': changes}
    return None
