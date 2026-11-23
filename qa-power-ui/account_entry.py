"""Verify signed-out navigation and family forms on the disposable cloud app."""
import json
import xml.etree.ElementTree as ET

import features as ui
from library_ui import field
from navigation import click
from route_content import destination_visible


def button_enabled(tree, label):
    parents = {child: parent for parent in tree.iter() for child in parent}
    for node in tree.iter('node'):
        if node.get('package') != ui.pkg or node.get('visible') == 'false' or label not in ui.labels(node):
            continue
        while node is not None and node.get('package') == ui.pkg:
            if node.get('enabled') == 'false':
                return False
            if node.get('clickable') == 'true':
                return True
            node = parents.get(node)
    return None


def verify_forms(out, capture):
    if ui.adb('get-serialno').strip() != '127.0.0.1:15555':
        raise RuntimeError('The family form test requires the local cloud Android transport')
    checks = []

    def record(name, predicate):
        shot = capture(out, 'entry-' + name, matches=predicate)
        tree = ET.parse(out / shot['tree']).getroot()
        passed = bool(predicate(tree))
        checks.append({'case': name, 'passed': passed, 'tree': shot['tree'], 'treeSha256': shot['treeSha256'],
                       'image': shot['image']})
        (out / 'account-entry-checks.json').write_text(json.dumps(checks, indent=2) + '\n', encoding='utf-8')
        if not passed:
            raise AssertionError('Signed-out navigation or family form failed: ' + name)

    def on_page(tree, route):
        return destination_visible(tree, route, ui.selected_dock)

    def name_state(tree, enabled):
        return on_page(tree, 'family-add') and button_enabled(tree, '保存') is enabled

    def first_pin(tree, enabled):
        editors = [n for n in tree.iter('node') if n.get('package') == ui.pkg and
                   n.get('visible') != 'false' and n.get('class', '').endswith('EditText')]
        labels = ui.page_info(tree)
        return (on_page(tree, 'family-pin') and len(editors) == 1 and
                not any('当前 PIN' in label for label in labels) and button_enabled(tree, '确认') is enabled)

    ui.launch()
    ui.root('我的')
    click(['设备接力', '跨设备接力'])
    record('handoff-login-action', lambda t: on_page(t, 'device-handoff') and button_enabled(t, '登录鱼服账号') is True)
    click('登录鱼服账号')
    record('handoff-one-tap-account', lambda t: on_page(t, 'account'))
    ui.shell('input', 'keyevent', 4)
    record('account-back-to-handoff', lambda t: on_page(t, 'device-handoff') and button_enabled(t, '登录鱼服账号') is True)

    ui.launch()
    ui.root('我的')
    click('家庭资料')
    click('新建家庭资料')
    record('empty-adult-name', lambda t: name_state(t, False) and not any('儿童资料需要' in s for s in ui.page_info(t)))
    name_label = '资料名称（1–40 个字）'
    for key, value, enabled in [('blank', '   ', False), ('one', 'Q', True),
                                ('forty', 'Q' * 40, True), ('forty-one', 'Q' * 41, False)]:
        field(name_label, value)
        record('family-name-' + key, lambda t: name_state(t, enabled))
    click('儿童资料')
    record('child-without-servers', lambda t: name_state(t, False) and
           any('添加服务器账号后' in s for s in ui.page_info(t)))
    click('取消')
    record('unsaved-profile-dismissed', lambda t: on_page(t, 'family') and 'Q' * 41 not in ui.page_info(t))

    click('设置家长 PIN')
    record('first-pin-empty', lambda t: first_pin(t, False))
    pin_label = '新 PIN（6–12 位）'
    for length in (5, 6, 12):
        field(pin_label, '7' * length, secret=True)
        record('first-pin-' + str(length), lambda t: first_pin(t, length >= 6))
    click('取消')
    record('unsaved-pin-dismissed', lambda t: on_page(t, 'family') and button_enabled(t, '设置家长 PIN') is True)

    click('设置家长 PIN')
    field(pin_label, '7' * 6, secret=True)
    click('确认')
    record('first-pin-saved', lambda t: on_page(t, 'family') and button_enabled(t, '修改家长 PIN') is True)
    click('修改家长 PIN')
    field(pin_label, '8' * 6, secret=True)
    record('change-requires-current-pin', lambda t: '当前 PIN' in ui.page_info(t) and button_enabled(t, '确认') is False)
    field('当前 PIN', '7' * 6, secret=True)
    record('valid-pin-change', lambda t: '当前 PIN' in ui.page_info(t) and button_enabled(t, '确认') is True)
    click('确认')
    record('changed-pin-saved', lambda t: on_page(t, 'family') and button_enabled(t, '修改家长 PIN') is True)

    member = 'Cloud QA'
    click('新建家庭资料')
    field(name_label, member)
    click('保存')
    ui.grab(member + ' · 成人', 10)
    record('valid-profile-saved', lambda t: member + ' · 成人' in ui.page_info(t))
    click('移除资料')
    record('remove-only-fixture-profile', lambda t: '移除家庭资料？' in ui.page_info(t) and
           any('“' + member + '”' in label for label in ui.page_info(t)))
    click('移除')
    ui.launch()
    ui.root('我的')
    click('家庭资料')
    record('family-after-fixture-removal', lambda t: on_page(t, 'family') and member + ' · 成人' not in ui.page_info(t))
