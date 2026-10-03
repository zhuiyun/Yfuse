#!/usr/bin/env python3
"""Temporary: rewrites PlayerRoot.kt so the giant PlaybackRuntimeContent lambda stops reading the
captured inPictureInPicture parameter late in its body, the value R8 9.1.31-9.4.20 clobbers.

usage: apply_variant.py <local|state> [PlayerRoot.kt]
"""

import re
import sys
from pathlib import Path

variant = sys.argv[1]
path = Path(sys.argv[2] if len(sys.argv) > 2 else "composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt")
lines = path.read_text(encoding="utf-8").split("\n")
anchor = next(i for i, line in enumerate(lines)
              if line.strip().startswith("val pictureInPictureFadeMs = if (LocalAccessibilityOptions.current.reduceMotion)"))
indent = lines[anchor][: len(lines[anchor]) - len(lines[anchor].lstrip())]
end = next(i for i in range(anchor, len(lines)) if lines[i] == "    }")  # closes PlaybackRuntimeContent's lambda

if variant == "local":
    name = "outsidePictureInPicture"
    insert = [f"{indent}val {name} = !inPictureInPicture"]
    pattern = re.compile(r"!inPictureInPicture(?!\w)")
elif variant == "state":
    name = "pictureInPicture"
    insert = [f"{indent}val {name} by rememberUpdatedState(inPictureInPicture)"]
    # Value uses only: a named argument's label keeps its name.
    pattern = re.compile(r"(?<![\w.])inPictureInPicture(?!\w)(?!\s*=(?!=))")
else:
    sys.exit(f"unknown variant {variant}")

changed = 0
for i in range(anchor + 1, end):
    new, count = pattern.subn(name, lines[i])
    lines[i] = new
    changed += count
lines[anchor + 1:anchor + 1] = insert
path.write_text("\n".join(lines), encoding="utf-8")
print(f"{variant}: inserted after line {anchor + 1}, rewrote {changed} uses up to line {end + 1}")
