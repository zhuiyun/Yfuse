"""Open the signed-out TV account page with a remote and verify both Back destinations."""
import json
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET

sys.path[:0] = ['qa-functional', 'qa-arm-matrix']
import engine
import features as ui
from capture import capture
from navigation import click


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    out = Path('results/tv-signed-out')
    out.mkdir(parents=True, exist_ok=True)
    rows = []

    def save(name, predicate):
        shot = capture(out, name, matches=predicate)
        tree = ET.parse(out / shot['tree']).getroot()
        ok = bool(predicate(tree))
        rows.append({'case': name, 'passed': ok, 'tree': shot['tree'], 'treeSha256': shot['treeSha256'],
                     'image': shot['image']})
        (out / 'checks.json').write_text(json.dumps(rows, indent=2) + '\n', encoding='utf-8')
        if not ok:
            raise AssertionError('TV remote navigation failed: ' + name)

    def is_handoff(tree):
        return {'在线设备', '登录鱼服账号'} <= set(ui.page_info(tree))

    def login_focused(tree):
        return any('登录鱼服账号' in ui.labels(child) for n in ui.nodes(tree)
                   if n.get('package') == ui.pkg and n.get('focused') == 'true' for child in n.iter())

    def is_account(tree):
        return {'账号', '密码'} <= set(ui.page_info(tree)) and any(
            n.get('package') == ui.pkg and n.get('class', '').endswith('EditText') for n in ui.nodes(tree))

    def leave_account(entry):
        keyboard = ui.keyboard_visible()
        if keyboard:
            ui.shell('input', 'keyevent', 4)
            deadline = time.monotonic() + 5
            while ui.keyboard_visible() and time.monotonic() < deadline:
                time.sleep(.15)
            if ui.keyboard_visible():
                raise AssertionError('TV Back did not dismiss the keyboard')
        shot = capture(out, entry + '-before-page-back', matches=is_account)
        if not is_account(ET.parse(out / shot['tree']).getroot()):
            raise AssertionError('TV left the account page before the page Back press')
        visible_before_back = ui.keyboard_visible()
        (out / (entry + '-ime.json')).write_text(json.dumps({
            'keyboardWasVisible': keyboard, 'keyboardVisibleBeforePageBack': visible_before_back,
            'accountTree': shot['tree'], 'accountImage': shot['image'],
        }) + '\n', encoding='utf-8')
        if visible_before_back:
            raise AssertionError('TV keyboard reopened before page Back')
        ui.shell('input', 'keyevent', 4)

    ui.adb('shell', 'am', 'start', '-W', '-n', 'com.yfuse/com.yfuse.tv.TvMainActivity', t=60)
    ui.wait('我的与设置', 60)
    click('我的与设置')
    click('设备接力')
    save('handoff-open', is_handoff)
    for _ in range(8):
        if login_focused(ui.snapshot()):
            break
        ui.shell('input', 'keyevent', 20)
        time.sleep(.3)
    save('login-focused', login_focused)
    ui.shell('input', 'keyevent', 23)
    save('remote-opened-account', is_account)
    leave_account('handoff')
    save('remote-back-to-handoff', is_handoff)
    ui.shell('input', 'keyevent', 4)
    save('remote-back-to-settings', lambda t: '搜索设置' in ui.page_info(t))
    click('账号与同步')
    save('direct-account', is_account)
    leave_account('direct')
    save('direct-account-back-to-settings', lambda t: '搜索设置' in ui.page_info(t))


if __name__ == '__main__':
    main()
