"""Remove baseline entries only for files independently checked by standalone ktlint."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
checked = set((OUT / "changed-kotlin.txt").read_text(encoding="utf-8").splitlines())
removed = {}
for baseline in sorted((ROOT / "config/ktlint").glob("*-baseline.xml")):
    module = baseline.name.removesuffix("-baseline.xml")
    with baseline.open(encoding="utf-8", newline="") as source:
        content = source.read()
    files = []

    def replace(match):
        name = f"{module}/{match.group(1)}"
        if name not in checked:
            return match.group(0)
        files.append({"file": name, "removed_errors": match.group(0).count("<error ")})
        return ""

    updated = re.sub(r'    <file name="([^"]+)">.*?</file>\r?\n', replace, content, flags=re.DOTALL)
    if updated != content:
        with baseline.open("w", encoding="utf-8", newline="") as output:
            output.write(updated)
        removed[baseline.relative_to(ROOT).as_posix()] = files
(OUT / "baseline-removals.json").write_text(json.dumps(removed, indent=2) + "\n", encoding="utf-8")
print(json.dumps(removed, indent=2))
