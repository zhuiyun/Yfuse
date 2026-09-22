"""Restore exact pinned lint dependencies using HTTPS and Maven's published checksums."""
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
REPO = OUT / "maven"
BASE = "https://repo.maven.apache.org/maven2/"
pending = [
    ("com.google.protobuf", "protobuf-java", "4.28.3", True),
    ("org.apache.commons", "commons-lang3", "3.18.0", True),
    ("org.junit", "junit-bom", "5.13.1", False),
    ("com.google.protobuf", "protobuf-bom", "4.28.3", False),
]
seen = set()
record = []
while pending:
    group, artifact, version, needs_jar = pending.pop(0)
    coordinate = (group, artifact, version)
    if coordinate in seen:
        continue
    seen.add(coordinate)
    stem = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}"
    for extension in (["pom", "jar"] if needs_jar else ["pom"]):
        relative = f"{stem}.{extension}"
        url = BASE + relative
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read()
        with urllib.request.urlopen(url + ".sha1", timeout=30) as response:
            expected = response.read().decode().strip().split()[0].lower()
        actual = hashlib.sha256(data).hexdigest()
        assert hashlib.sha1(data).hexdigest() == expected, relative
        target = REPO / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        record.append({"path": relative, "sha256": actual, "published_sha1": expected, "url": url})
        if extension == "pom":
            parent = ET.fromstring(data).find("{http://maven.apache.org/POM/4.0.0}parent")
            if parent is not None:
                values = tuple(parent.findtext(f"{{http://maven.apache.org/POM/4.0.0}}{key}")
                               for key in ("groupId", "artifactId", "version"))
                assert all(values) and not any("${" in value for value in values), values
                pending.append((*values, False))
(OUT / "lint-dependencies.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
(OUT / "lint-dependencies.init.gradle").write_text(
    "settingsEvaluated { settings ->\n"
    "    settings.dependencyResolutionManagement.repositories.exclusiveContent {\n"
    "        forRepository {\n"
    "            settings.dependencyResolutionManagement.repositories.maven {\n"
    f"                url = new File('{REPO.as_posix()}').toURI()\n"
    "            }\n"
    "        }\n"
    "        filter {\n" +
    "".join(f"            includeVersion('{group}', '{artifact}', '{version}')\n"
            for group, artifact, version in sorted(seen)) +
    "        }\n"
    "    }\n"
    "}\n", encoding="utf-8"
)
print(f"Verified {len(record)} artifacts; staged {len(seen)} pinned coordinates.")
