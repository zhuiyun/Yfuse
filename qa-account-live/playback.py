"""Real phone-to-TV handoff using a run-unique, loopback-only movie."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

if os.environ.get('GITHUB_ACTIONS') != 'true': raise SystemExit('Cloud runner required')
sys.path[:0] = ['qa-arm-original', 'qa-arm-matrix', 'qa-power-ui', 'qa-functional']
import direct
import engine
import features as ui
from ext_run import adb
from background_audio import session
from library import item
from media import MediaServer
from navigation import click


def prepare(fixture, output):
    title = 'Cloud QA Playback ' + os.environ['GITHUB_RUN_ID']
    movie = item('account-qa-' + os.environ['GITHUB_RUN_ID'], title)
    movie['RunTimeTicks'] = 6_000_000_000
    fixture.movies = [movie] + fixture.movies
    fixture.items[movie['Id']] = movie
    original_source = fixture.source
    def source(key):
        value = original_source(key); value['RunTimeTicks'] = movie['RunTimeTicks']; return value
    fixture.source = source
    media = Path(os.environ['RUNNER_TEMP'])/'account-handoff.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
                    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '600',
                    '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '30', '-c:a', 'aac',
                    '-movflags', '+faststart', str(media)], check=True)
    return title, MediaServer(media, output/'handoff-media-http.jsonl')


def run(coordinator, television_name, title, output, use, capture, wait_text, passed):
    for role in ('phone', 'tv'):
        use(role); adb('reverse', 'tcp:18389', 'tcp:18389')
    use('phone')
    for _ in range(8):
        dock = ui.dock(ui.snapshot(), '媒体库')
        if dock is not None:
            ui.tap(dock); break
        adb('shell', 'input', 'keyevent', '4'); time.sleep(.5)
    else: raise AssertionError('Phone library dock unavailable without process restart')
    wait_text(title, 25); click(title); click('播放')
    proof = direct.decode_playback('phone-handoff-source', None, 600)
    (output/'phone-before-handoff.json').write_text(json.dumps(proof))
    assert engine.engine_evidence('YCore')['active'], 'Phone YCore native output unavailable'
    passed('phone-handoff-source-advances-picture-and-time')
    # A nonzero position makes accidental fresh playback observable.
    (left, top, right, bottom), _ = direct.reveal(); y = (top+bottom)//2
    adb('shell', 'input', 'swipe', round(left+(right-left)*.1), y, round(left+(right-left)*.35), y, 350)
    proof = direct.decode_playback('phone-handoff-seek', 210, 600)
    (output/'phone-handoff-seek.json').write_text(json.dumps(proof))
    passed('phone-handoff-source-seek-confirmed')
    deadline = time.monotonic()+60
    while time.monotonic() < deadline:
        devices = coordinator.api('/api/v1/account/handoff')['devices']
        targets = [d for d in devices if d['sessionId'] == coordinator.state['created']['tv'] and d.get('canReceive')]
        if targets: break
        time.sleep(1)
    assert len(targets) == 1 and targets[0]['name'] == television_name
    assert sum(1 for d in devices if d['name'] == television_name and d.get('canReceive')) == 1
    direct.reveal(); click('更多'); click('设备接力'); wait_text('接力到 '+television_name, 30)
    click('接力到 '+television_name); capture('phone-handoff-offered')
    before_accept = session(output, 'source-before-tv-accept')
    assert before_accept['state'] == 3, 'Source paused before receiver accepted'
    # Source keeps playing while the user navigates and the receiver confirms.
    # Compare with the offer-time position, not the earlier seek proof.
    position = before_accept['positionMs'] / 1000
    passed('source-keeps-playing-until-tv-accepts')
    use('tv'); wait_text('接收', 30); capture('tv-handoff-offer')
    for _ in range(6):
        focused = [node for node in ui.nodes(ui.snapshot()) if node.get('focused') == 'true']
        if any('接收' in ui.labels(child) for node in focused for child in node.iter()): break
        adb('shell', 'input', 'keyevent', '22'); time.sleep(.4)
    else: raise AssertionError('D-pad did not focus the TV handoff Receive action')
    capture('tv-handoff-receive-focus')
    adb('shell', 'input', 'keyevent', '23')
    passed('tv-handoff-accepted-with-dpad')
    try:
        proof = direct.decode_playback('tv-handoff-output', position, 600)
    except Exception:
        capture('tv-handoff-decode-failure')
        direct.capture_failure('tv-handoff-output')
        session(output, 'tv-handoff-failure')
        raise
    assert engine.engine_evidence('SenPlayer')['active'], 'TV SenPlayer native output unavailable'
    (output/'tv-handoff-decoded.json').write_text(json.dumps(proof)); capture('tv-handoff-playing')
    passed('tv-handoff-restores-position-with-advancing-picture')
    use('phone')
    deadline = time.monotonic()+20
    while time.monotonic() < deadline:
        if session(output, 'source-after-tv-ready')['state'] == 2: break
        time.sleep(.5)
    else: raise AssertionError('Source did not pause after TV became ready')
    first = session(output, 'source-paused-first'); time.sleep(2)
    second = session(output, 'source-paused-second')
    assert second['state'] == 2 and abs(second['positionMs']-first['positionMs']) <= 300
    passed('source-pauses-only-after-tv-ready')


def remove_test_history(title, output, use, capture, wait_text, passed):
    # Stop the test receiver before removing only this run's uniquely named record.
    use('tv'); adb('shell', 'am', 'force-stop', 'com.yfuse')
    use('phone')
    # Close the player activity through normal activity navigation. Do not kill
    # the signed-in process: legacy servers rotate its session ID on restoration.
    adb('shell', 'am', 'start', '-W', '--activity-clear-top', '--activity-single-top',
        '-n', 'com.yfuse/com.yfuse.MainActivity')
    ui.root('我的'); click('我的片单'); click('观看历史')
    wait_text('观看历史', 20)
    from navigation import control
    control(title, scrolls=20)
    nodes = [n for n in ui.nodes(ui.snapshot()) if n.get('text') == title]
    assert len(nodes) == 1, 'Ambiguous test history record; leave unrelated records untouched'
    x, y = ui.coords(nodes[0]); adb('shell', 'input', 'swipe', x, y, x, y, 900)
    suffix = title.rsplit(' ', 1)[-1]
    wait_text('移除观看记录', 15); capture('test-history-remove-confirmation-'+suffix)
    click('移除观看记录')
    # Accessibility extends the undo notice to 60 seconds. Its disappearance,
    # not a fixed 7-second wait, commits the pending removal in the product.
    removed_notice = '已移除「' + title + '」的观看记录'
    wait_text(removed_notice, 15); click(removed_notice)
    deadline = time.monotonic()+20
    while ui.find(ui.snapshot(), removed_notice) is not None:
        assert time.monotonic() < deadline, 'Removal notice did not settle'
        time.sleep(.3)
    tree = capture('test-history-removed-'+suffix)
    assert not any(n.get('text') == title for n in ui.nodes(tree)), 'Test history remained visible'
    passed('only-run-unique-playback-history-removed')
    verify_test_history_absent([title], output, use, capture, wait_text, passed, already_in_history=True)
    ui.root('我的'); click('同步状态与恢复'); click('立即同步')
    wait_text('个人数据已同步', 45); capture('test-history-synchronized-'+suffix)
    passed('run-unique-history-removal-committed-and-synchronized')


def verify_test_history_absent(titles, output, use, capture, wait_text, passed, already_in_history=False):
    from library_ui import field
    use('phone')
    if not already_in_history:
        ui.root('我的'); click('我的片单'); click('观看历史')
    click('搜索片单')
    for title in titles:
        assert title.startswith('Cloud QA Playback ') and title.rsplit(' ', 1)[-1].isdigit()
        field('搜索历史', title); ui.hide_keyboard()
        wait_text('没有找到「' + title + '」', 25)
        capture('test-history-absence-'+title.rsplit(' ', 1)[-1])
    click('收起搜索')
    passed('run-unique-history-absence-confirmed-by-full-list-search')
