from pathlib import Path

path = Path("watchTogetherServer/gradle.lockfile")
text = path.read_text()
old = "org.xerial:sqlite-jdbc:3.53.2.1="
new = "org.xerial:sqlite-jdbc:3.53.4.0="
if old not in text:
    if new in text:
        print("sqlite lock already aligned")
        raise SystemExit(0)
    raise SystemExit("sqlite-jdbc lock entry not found")
path.write_text(text.replace(old, new, 1))
print("sqlite-jdbc dependency lock aligned to 3.53.4.0")
