import subprocess
from pathlib import Path

out = Path(__file__).resolve().parent
for theme in ("light", "dark"):
    for stage in ("off", "early", "later", "reduced", "disabled"):
        name = f"{theme}-{stage}.png"
        image = subprocess.run(
            ["D:/AndroidSDK/platform-tools/adb.exe", "-s", "RF8M223V4MD", "exec-out", "run-as", "com.yfuse.diagnosticsfix", "cat", f"cache/{name}"],
            capture_output=True, check=True,
        ).stdout
        assert image.startswith(b"\x89PNG\r\n\x1a\n")
        (out / name).write_bytes(image)
print("Saved 10 actual SearchField screenshots.")
