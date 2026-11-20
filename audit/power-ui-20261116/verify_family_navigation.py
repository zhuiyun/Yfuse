"""Check the saved phone and TV account/family screens and their run provenance."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
RAW = ROOT.parent / 'legacy-engine-20261113/power-ui-20261116'
PHONE = ('handoff-login-action', 'handoff-one-tap-account', 'account-back-to-handoff',
         'empty-adult-name', 'family-name-blank', 'family-name-one', 'family-name-forty',
         'family-name-forty-one', 'child-without-servers', 'unsaved-profile-dismissed',
         'first-pin-empty', 'first-pin-5', 'first-pin-6', 'first-pin-12', 'unsaved-pin-dismissed',
         'first-pin-saved', 'change-requires-current-pin', 'valid-pin-change', 'changed-pin-saved',
         'valid-profile-saved', 'remove-only-fixture-profile', 'family-after-fixture-removal')
TV = ('handoff-open', 'login-focused', 'remote-opened-account', 'remote-back-to-handoff',
      'remote-back-to-settings', 'direct-account', 'direct-account-back-to-settings')


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def words(node):
    return set(line.strip() for key in ('text', 'content-desc')
               for line in node.get(key, '').splitlines() if line.strip())


def inspect(tree, name):
    nodes = [n for n in tree.iter('node') if n.get('visible') != 'false' and n.get('package') == 'com.yfuse']
    labels = set().union(*(words(n) for n in nodes))
    edits = [n for n in nodes if n.get('class', '').endswith('EditText')]
    parents = {child: parent for parent in tree.iter() for child in parent}

    def has(fragment):
        return any(fragment in label for label in labels)

    def button(label):
        for node in nodes:
            if label not in words(node):
                continue
            target = node
            while target is not None and target.get('package') == 'com.yfuse':
                if target.get('enabled') == 'false':
                    return False
                if target.get('clickable') == 'true':
                    return True
                target = parents.get(target)
        return None

    family = {'管理资料', '新建家庭资料'} <= labels
    member = 'Cloud QA · 成人'
    if name in ('handoff-login-action', 'account-back-to-handoff', 'handoff-open', 'remote-back-to-handoff'):
        return has('在线设备') and button('登录鱼服账号') is True
    if name == 'handoff-one-tap-account':
        return has('登录 Yfuse 账号') and has('请输入账号名') and bool(edits)
    if name in ('empty-adult-name', 'child-without-servers') or name.startswith('family-name-'):
        enabled = name in ('family-name-one', 'family-name-forty')
        return (has('资料名称') and bool(edits) and button('保存') is enabled and
                (name != 'empty-adult-name' or not has('儿童资料需要')) and
                (name != 'child-without-servers' or has('添加服务器账号后')))
    if name in ('first-pin-empty', 'first-pin-5', 'first-pin-6', 'first-pin-12'):
        return (has('新 PIN') and not has('当前 PIN') and len(edits) == 1 and
                button('确认') is (name in ('first-pin-6', 'first-pin-12')))
    if name in ('change-requires-current-pin', 'valid-pin-change'):
        return has('当前 PIN') and len(edits) >= 2 and button('确认') is (name == 'valid-pin-change')
    if name == 'unsaved-pin-dismissed':
        return family and button('设置家长 PIN') is True
    if name in ('first-pin-saved', 'changed-pin-saved'):
        return family and button('修改家长 PIN') is True
    if name == 'unsaved-profile-dismissed':
        return family and not has('Q' * 41)
    if name == 'valid-profile-saved':
        return member in labels
    if name == 'remove-only-fixture-profile':
        return '移除家庭资料？' in labels and has('“Cloud QA”')
    if name == 'family-after-fixture-removal':
        return family and member not in labels
    if name == 'login-focused':
        return any(n.get('focused') == 'true' and any('登录鱼服账号' in words(child) for child in n.iter())
                   for n in nodes)
    if name in ('remote-opened-account', 'direct-account'):
        return {'账号', '密码'} <= labels and bool(edits)
    if name in ('remote-back-to-settings', 'direct-account-back-to-settings'):
        return '搜索设置' in labels
    raise ValueError(name)


def old_controls():
    before = read(ROOT / 'account-family-before-50.json')
    cases = {'family-pin': 'first-pin-empty', 'family-add': 'empty-adult-name',
             'device-handoff': 'handoff-login-action'}
    for r in before['cases']:
        tree = ROOT.parent / r['tree']
        assert hashlib.sha256(tree.read_bytes()).hexdigest() == r['treeSha256']
        assert not inspect(ET.parse(tree).getroot(), cases[r['route']])
    assert len(before['cases']) == 6
    return len(before['cases'])


def verify_sheet(folder, expected, rows):
    assert [r['case'] for r in rows] == list(expected)
    checks = []
    for row in rows:
        name = row['case']
        base = ('entry-' + name) if expected == PHONE else name
        assert (row['tree'], row['image']) == (base + '.xml', base + '.png')
        tree = ET.parse(folder / row['tree']).getroot()
        assert hashlib.sha256(ET.tostring(tree)).hexdigest() == row['treeSha256']
        image = (folder / row['image']).read_bytes()
        assert image.startswith(b'\x89PNG\r\n\x1a\n') and len(image) > 100
        checks.append({'case': name, 'verified': row['passed'] is True and bool(inspect(tree, name)),
                       'treeSha256': row['treeSha256'], 'pngSha256': hashlib.sha256(image).hexdigest()})
    return checks


def verify(number):
    source = read(ROOT / f'iteration-{number}-verification.json')
    package = read(ROOT / f'package-verification-{number}.json')
    root = RAW / f'full-ui-{number}'
    owner = read(root / 'workflow-artifacts.json')
    assert owner['repo'] == 'zhuiyun/Yfuse' and owner['run'] == source['uiRun']
    known = {a['name']: a for a in owner['artifacts']}
    output = {}
    for theme in ['light', 'dark-large']:
        folder = root / ('ui-verified-secondary-' + theme)
        index = known[folder.name]
        receipt = read(folder / 'download-receipt.json')
        assert index['id'] == receipt['id'] and index['digest'] == receipt['digest']
        assert index['workflowHead'] == source['uiWorkflowHead']
        assert 'app=' + source['source'] in (folder / 'provenance.txt').read_text().splitlines()
        assert (folder / 'apk.sha256').read_text().split()[0] == package['apkSha256']
        phone = folder / 'ui'
        output[theme] = verify_sheet(phone, PHONE, read(phone / 'account-entry-checks.json'))
        if theme == 'light':
            tv = folder / 'tv-signed-out'
            build = read(tv / 'tv-build.json')
            assert (build['id'], build['head_sha'], build['conclusion']) == (source['tvRun'], source['source'], 'success')
            assert re.fullmatch('[a-f0-9]{64}', (tv / 'tv-apk.sha256').read_text().split()[0])
            output['tv'] = verify_sheet(tv, TV, read(tv / 'checks.json'))
    before = old_controls()
    result = {'round': number, 'source': source['source'], 'run': owner['run'], 'oldBuildFailuresReproduced': before,
              'passed': all(r['verified'] for checks in output.values() for r in checks), 'checks': output}
    (ROOT / f'family-navigation-{number}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n',
                                                           encoding='utf-8')
    return result['passed']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int, nargs='?')
    args = parser.parse_args()
    if args.round is None:
        print('Verified previous-build failures in', old_controls(), 'archived screenshots and trees')
    else:
        raise SystemExit(0 if verify(args.round) else 1)
