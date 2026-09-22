"""Stage exact Maven Central artifacts for the local JVM's restricted network environment."""
import hashlib
import json
import sys
from pathlib import Path
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
MAVEN = ROOT.parents[1] / ".gradle-tmp/health-maven"
BASE = "https://repo.maven.apache.org/maven2/"
record_path = ROOT / "maven-checksums.json"
records = json.loads(record_path.read_text()) if record_path.exists() else []
seen = set()
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fetch(relative, optional=False):
    try:
        with urllib.request.urlopen(BASE + relative, timeout=30) as response:
            data = response.read()
    except urllib.error.HTTPError as error:
        if optional and error.code == 404:
            return None
        raise
    for algorithm in ("sha256", "sha512", "sha1"):
        try:
            with urllib.request.urlopen(BASE + relative + "." + algorithm, timeout=30) as response:
                expected = response.read().decode().split()[0].lower()
            break
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    else:
        raise RuntimeError("Missing Maven checksum: " + relative)
    actual = hashlib.sha256(data).hexdigest()
    if hashlib.new(algorithm, data).hexdigest() != expected:
        raise RuntimeError("Maven checksum mismatch: " + relative)
    target = MAVEN / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    records.append({"url": BASE + relative, "sha256": actual, "size": len(data),
                    "upstreamChecksumAlgorithm": algorithm, "upstreamChecksum": expected})
    return data


def stage(group, name, version, pom_only=False):
    coordinate = (group, name, version)
    if coordinate in seen:
        return
    seen.add(coordinate)
    relative = group.replace(".", "/") + f"/{name}/{version}/{name}-{version}"
    pom = ET.fromstring(fetch(relative + ".pom"))
    parent = pom.find("m:parent", NS)
    if parent is not None:
        stage(*(parent.findtext("m:" + key, namespaces=NS) for key in ("groupId", "artifactId", "version")), pom_only=True)
    if not pom_only:
        fetch(relative + ".module", optional=True)
        if pom.findtext("m:packaging", namespaces=NS) != "pom":
            fetch(relative + ".jar")
    print(":".join(coordinate), flush=True)


if sys.argv[1:]:
    for coordinate in sys.argv[1:]:
        stage(*coordinate.split(":"))
else:
    for artifact in ("bcprov-jdk18on", "bcpg-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on"):
        stage("org.bouncycastle", artifact, "1.85")
    for artifact in ("wire-runtime", "wire-runtime-jvm"):
        stage("com.squareup.wire", artifact, "6.4.5")
    stage("org.apache.commons", "commons-lang3", "3.18.0")
    stage("org.apache.httpcomponents", "httpclient", "4.5.14")
record_path.write_text(json.dumps(list({entry["url"]: entry for entry in records}.values()), indent=2) + "\n", encoding="utf-8")
