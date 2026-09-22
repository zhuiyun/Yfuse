from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKUP = Path(__file__).parent / 'before'

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, content):
    target = ROOT / path
    backup = BACKUP / path
    if target.exists() and not backup.exists():
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_bytes(target.read_bytes())
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8', newline='\n')

def replace(path, old, new):
    text = read(path)
    assert old in text, (path, old[:80])
    write(path, text.replace(old, new))
