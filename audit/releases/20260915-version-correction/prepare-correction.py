from pathlib import Path
import re

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]

def resolve_ours(text):
    return re.sub(r"^<<<<<<< CURRENT-WORKSPACE\n(.*?)^=======\n.*?^>>>>>>> DELIVERED-1.0.64\n", r"\1", text, flags=re.M | re.S)

script = resolve_ours((AUDIT / 'merge-inputs/scripts/supply_chain_check.py/merged').read_text(encoding='utf-8'))
script = script.replace('if not isinstance(result_item, dict) or "error" in result_item:',
                        'if (not isinstance(result_item, dict) or "error" in result_item\n'
                        '                    or result_item.get("next_page_token")):')
(ROOT / 'scripts/supply_chain_check.py').write_text(script, encoding='utf-8')
tests = resolve_ours((AUDIT / 'merge-inputs/scripts/test_supply_chain_check.py/merged').read_text(encoding='utf-8'))
tests = tests.replace('    severity,\n    Dependency,\n    read_dependencies,\n    query_osv,\n', '    severity,\n')
tests = tests.replace('            (root / "gradle.lockfile").write_text("example:locked:1.0=runtime\\n")\n',
                      '            (root / "gradle.lockfile").write_text("example:locked:1.0=runtime\\n")\n'
                      '            subprocess.run(["git", "init", "-q", str(root)], check=True)\n'
                      '            subprocess.run(["git", "-C", str(root), "add", "gradle.lockfile"], check=True)\n')
(ROOT / 'scripts/test_supply_chain_check.py').write_text(tests, encoding='utf-8')

notes = (ROOT / 'release-notes.txt').read_text(encoding='utf-8')
baseline_notes = (AUDIT / 'baseline-source/release-notes.txt').read_text(encoding='utf-8')
current_changes = notes.split('\n1.0.55\n', 1)[0].split('\n', 1)[1].strip()
assert notes.splitlines()[0] == '1.0.56'
assert baseline_notes.splitlines()[0] == '1.0.64'
assert notes.split('\n1.0.55\n', 1)[1] == baseline_notes.split('\n1.0.55\n', 1)[1]
(ROOT / 'release-notes.txt').write_text(
    '1.0.65\n\n' + current_changes + '\n'
    '• 接续已交付的 1.0.64（226），保留关闭页缩小收圆、仅上下氛围光、粒子样式及 YCore 原生稳定性修复。\n\n'
    + baseline_notes, encoding='utf-8')
version = (ROOT / 'version.properties').read_text(encoding='utf-8')
version = re.sub(r'^VERSION_CODE=.*$', 'VERSION_CODE=227', version, flags=re.M)
version = re.sub(r'^VERSION_NAME=.*$', 'VERSION_NAME=1.0.65', version, flags=re.M)
(ROOT / 'version.properties').write_text(version, encoding='utf-8')
old = ROOT / 'artifacts/releases/ambient-family-1.0.56-218'
notice = ('已撤回：此本地包误以 1.0.55（217）为版本基线。\n'
          '已核实上次实际交付包为 1.0.64（226），故本次改动改由 1.0.65（227）交付。\n'
          '1.0.56（218）包仅保留用于审计，请勿作为最新版安装或分发。\n'
          '原版本说明中的本轮改动已归入根 release-notes.txt 的 1.0.65，原记录保留在本目录。\n')
(old / 'SUPERSEDED.txt').write_text(notice, encoding='utf-8')
for readme in (old / 'README.md', ROOT / 'audit/releases/20260915-ambient-family/README.md'):
    if readme.exists():
        readme.write_text('# 已撤回：版本基线错误\n\n' + notice + '\n---\n\n'
                          + readme.read_text(encoding='utf-8'), encoding='utf-8')
print('Resolved both script conflicts; updated metadata to 1.0.65 (227); withdrew mistaken local APK.')
