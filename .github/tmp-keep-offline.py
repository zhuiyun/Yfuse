"""Temporary: makes a copy of the smoke script leave Wi-Fi and mobile data off after its offline case."""

import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text()
enable = (
    '            self.adb("shell", "svc", "wifi", "enable")\n'
    '            self.adb("shell", "svc", "data", "enable")\n'
)
assert enable in text, "offline() changed; update the probe"
path.write_text(text.replace(enable, "            pass\n", 1))
