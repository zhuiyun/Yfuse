from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    target = ROOT / path
    backup = OUT / 'before' / path
    if target.exists() and not backup.exists():
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_bytes(target.read_bytes())
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8', newline='\n')
    manifest = OUT / 'changed-files.txt'
    entries = manifest.read_text(encoding='utf-8').splitlines() if manifest.exists() else []
    if path not in entries:
        entries.append(path)
        manifest.write_text('\n'.join(entries) + '\n', encoding='utf-8')

def replace(path, old, new):
    text = read(path)
    assert old in text, (path, old[:100])
    write(path, text.replace(old, new))
