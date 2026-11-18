"""Compare full-screen video cadence, sampled CPU functions and process CPU on the same guest."""
import hashlib
import json
import os
from pathlib import Path
import re
import time

from PIL import Image

from cast_receiver import clock_second
from media import MediaServer
from output_size_cpu import cpu_percent, cpu_sample
from playback_cpu import profile
from power import adb, engine, playing, screen
from prepare_buffer_clock import create
from run_buffer_study import picture_coverage


OUT = Path('results/cadence')


def layers(text):
    result = []
    for section in re.split(r'(?m)^\s*layerName\s*=\s*', text)[1:]:
        name = section.splitlines()[0].strip()
        owner = re.search(r'(?m)^\s*packageName\s*=\s*([^\n]+)', section)
        if 'com.yfuse' not in name and not (owner and owner[1].strip() == 'com.yfuse'):
            continue
        values = {'name': name}
        for key in ('totalFrames', 'droppedFrames', 'averageFPS'):
            match = re.search(r'(?m)^\s*' + key + r'\s*=\s*([\d.]+)', section)
            if match:
                values[key] = float(match[1]) if key == 'averageFPS' else int(match[1])
        result.append(values)
    return result


def picture(label):
    file = OUT / (label + '.png')
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        frame = screen().convert('RGB')
        if frame.size != (1280, 720):
            raise AssertionError('The fixed landscape window changed size')
        second = clock_second(frame.resize((160, 90), Image.Resampling.BOX))
        if second is not None:
            frame.save(file)
            field = picture_coverage(file)
            if field['fullField']:
                return {'second': second, 'field': field, 'pictureSha256': hashlib.sha256(file.read_bytes()).hexdigest()}
        time.sleep(.25)
    raise AssertionError('Missing complete six-bar picture and independent timecode in ' + label)


def window(index, core, server, clock_hz):
    label = str(index) + '-' + core
    adb('shell', 'am', 'force-stop', 'com.yfuse')
    engine.configure(core)
    report = {'core': core, 'id': label, 'started': playing(core, 'http://127.0.0.1:18389/' + label + '.mp4')}
    report['before'] = picture(label + '-before')
    traffic = server.counters()
    adb('shell', 'dumpsys', 'SurfaceFlinger', '--timestats', '-clear', '-enable')
    try:
        start = time.monotonic()
        first = cpu_sample()
        time.sleep(15)
        last = cpu_sample()
        report['elapsedSeconds'] = time.monotonic() - start
        report['cpuPercentOneCore'] = cpu_percent(first, last, clock_hz)
        stats = adb('shell', 'dumpsys', 'SurfaceFlinger', '--timestats', '-dump', '-maxlayers', '64')
    finally:
        adb('shell', 'dumpsys', 'SurfaceFlinger', '--timestats', '-disable', '-clear', check=False)
    (OUT / (label + '-layers.txt')).write_text(stats, encoding='utf8')
    report['layers'] = layers(stats)
    report['after'] = picture(label + '-after')
    report['httpBytesInWindow'] = server.counters()['bytes'] - traffic['bytes']
    tracing = os.environ.get('QA_TRACE_MATH_CALLERS') == 'true'
    report['sample'] = profile(include_symbols=True, trace_math_callers=tracing)
    sampled_math = any(row['symbol'] == '__ieee754_powf' for row in report['sample'].get('symbols', []))
    callers = report['sample'].get('mathCallerReport', {})
    report['afterProfile'] = picture(label + '-after-profile')
    complete = (report['started']['identity']['engine'] == core and report['started']['identity']['active']
                and abs(report['after']['second'] - report['before']['second'] - report['elapsedSeconds']) <= 4
                and report['afterProfile']['second'] - report['after']['second'] >= 16
                and report['cpuPercentOneCore']['com.yfuse'] is not None and report['cpuPercentOneCore']['surfaceflinger'] is not None
                and any(v.get('totalFrames', 0) >= 120 and v.get('averageFPS', 0) > 0 for v in report['layers'])
                and report['sample']['status'] == 'recorded' and report['sample']['symbols']
                and (not tracing or not sampled_math or
                     (callers.get('exitCode') == 0 and any('__ieee754_powf' in line for line in callers['lines']))))
    report['complete'] = bool(complete)
    return report


def main():
    engine.guard()
    if adb('get-serialno').strip() != '127.0.0.1:15555':
        raise RuntimeError('Disposable cloud Android required')
    OUT.mkdir(parents=True, exist_ok=True)
    fixture = Path(os.environ['RUNNER_TEMP']) / 'cadence-clock.mp4'
    create(fixture)
    server = MediaServer(fixture, OUT / 'media.jsonl')
    adb('reverse', 'tcp:18389', 'tcp:18389')
    graphics = adb('shell', 'dumpsys', 'SurfaceFlinger')
    result = {'fixtureSha256': hashlib.sha256(fixture.read_bytes()).hexdigest(), 'contentFps': 24, 'windows': [],
              'graphics': {'renderer': [s.strip() for s in graphics.splitlines() if re.match(r'^\s*GLES:', s)],
                           'egl': adb('shell', 'getprop', 'ro.hardware.egl').strip(),
                           'vulkan': adb('shell', 'getprop', 'ro.hardware.vulkan').strip()}}
    clock_hz = int(adb('shell', 'getconf', 'CLK_TCK'))
    assert clock_hz > 0
    try:
        for index, core in enumerate(('YCore', 'SenPlayer', 'SenPlayer', 'YCore')):
            try:
                observation = window(index, core, server, clock_hz)
            except (RuntimeError, AssertionError, OSError, ValueError) as error:
                observation = {'core': core, 'id': str(index) + '-' + core, 'complete': False, 'error': str(error)}
            result['windows'].append(observation)
            (OUT / 'observations.json').write_text(json.dumps(result, indent=2), encoding='utf8')
    finally:
        server.close()
    if not all(row['complete'] for row in result['windows']):
        raise SystemExit('One or more full-screen windows lacked cadence or CPU attribution evidence')


if __name__ == '__main__':
    main()
