"""Eight public movies/episodes and every existing stress format, in one process."""
import json
import subprocess
import sys
from pathlib import Path

files = []
for index in (0, 1, 2, 3, 68, 69, 70, 71):
    subprocess.run([sys.executable, "qa-title-seek-check/prepare.py", str(index), "86"], check=True)
    selected = json.loads(Path("qa-media/fixtures.json").read_text())
    if len(selected) != 1:
        raise ValueError("Missing continuous-playback fixture")
    files.extend(selected)
if {row["kind"] for row in files} != {"movie", "episode"}:
    raise ValueError("Both movies and episodes are required")
Path("qa-media/fixtures.json").write_text(json.dumps(files))
Path("results/continuous-sources.json").write_text(json.dumps(files))
