"""Reject retired player implementations in production source, including unused UI."""
import os
from pathlib import Path
import re
import sys


MODULES = ("composeApp", "phoneShared", "tvApp", "tvShared", "senplayerBridge", "harmonyApp", "castReceiver", "ycore-native", "scripts/native")
EXTENSIONS = {".kt", ".java", ".swift", ".ets", ".js", ".ts", ".rs", ".cpp", ".h"}
DECLARATION = re.compile(r"\b(?:fun|class|interface|object|typealias|func|function|struct|trait|fn)\s+(?:<[^>\n]+>\s+)?([A-Za-z_]\w*)")
WORDS = re.compile(r"[A-Z]+(?=[A-Z][a-z]|[^A-Za-z]|$)|[A-Z]?[a-z]+|[0-9]+")
RETIRED = {"exo", "exoplayer", "mpv", "mdk"}
IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?(?:androidx\.media3\.exoplayer|com\.google\.android\.exoplayer2|is\.xyz\.mpv|com\.mediadevkit\.sdk)\b")


def violations(source):
    for line_number, line in enumerate(source.splitlines(), 1):
        for name in DECLARATION.findall(line):
            if RETIRED.intersection(word.lower() for word in WORDS.findall(name)):
                yield line_number, name
        if IMPORT.match(line):
            yield line_number, line.strip()


def main():
    root = Path(__file__).resolve().parents[1]
    checked = 0
    failed = False
    for module in MODULES:
        base = root / module
        sources = base / "src" if (base / "src").is_dir() else base
        for current, directories, files in os.walk(sources):
            directories[:] = [name for name in directories if "test" not in name.lower() and name not in ("build", "node_modules", ".gradle", "target")]
            for filename in files:
                path = Path(current, filename)
                if path.suffix not in EXTENSIONS:
                    continue
                checked += 1
                for line, detail in violations(path.read_text(encoding="utf8")):
                    print(f"{path.relative_to(root)}:{line}: {detail}")
                    failed = True
    print(f"Checked {checked} production source files for retired player declarations and imports.")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
