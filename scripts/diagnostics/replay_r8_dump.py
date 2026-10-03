#!/usr/bin/env python3
"""Recompile an R8 input dump with another R8 release.

AGP's R8 writes its complete input (program classes, libraries, merged keep rules, min API and
mode) as a zip when the JVM runs with -Dcom.android.tools.r8.dumpinputtodirectory=<dir>. Replaying
that dump with a given r8lib.jar shows what exactly that R8 version would put in the APK, so a
compiler defect can be checked against newer releases without changing the build.

usage: replay_r8_dump.py --dump dump.zip --r8 r8lib.jar --output out.zip
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def properties(text: str) -> dict[str, str]:
    values = {}
    for line in text.splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dump", required=True, type=Path)
    parser.add_argument("--r8", required=True, type=Path, help="r8lib.jar of the release to replay with")
    parser.add_argument("--output", required=True, type=Path, help="zip to write the dex files to")
    parser.add_argument("--heap", default="4g")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as work:
        root = Path(work)
        with zipfile.ZipFile(args.dump) as dump:
            dump.extractall(root)
        build = properties((root / "build.properties").read_text(encoding="utf-8"))
        if build.get("tool") != "R8":
            sys.exit(f"{args.dump} is a {build.get('tool')} dump, not R8")
        command = [
            "java", f"-Xmx{args.heap}", "-cp", str(args.r8), "com.android.tools.r8.R8",
            "--release" if build.get("mode", "release") == "release" else "--debug",
            "--min-api", build["min-api"],
            "--lib", str(root / "library.jar"),
            "--pg-conf", str(root / "proguard.config"),
            "--output", str(args.output),
        ]
        if (root / "classpath.jar").is_file():
            command += ["--classpath", str(root / "classpath.jar")]
        if build.get("desugar-state") == "OFF":
            command.append("--no-desugaring")
        if (root / "desugared-library.json").is_file():
            command += ["--desugared-lib", str(root / "desugared-library.json")]
        if (root / "main-dex-rules.txt").is_file():
            command += ["--main-dex-rules", str(root / "main-dex-rules.txt")]
        for profile in sorted(root.glob("startup-profile*.txt")):
            command += ["--startup-profile", str(profile)]
        for profile in sorted(root.glob("art-profile*.txt")):
            command += ["--art-profile", str(profile), str(root / f"residual-{profile.name}")]
        command.append(str(root / "program.jar"))

        version = subprocess.run(
            ["java", "-cp", str(args.r8), "com.android.tools.r8.R8", "--version"],
            capture_output=True, text=True,
        ).stdout.strip()
        dumped = (root / "r8-version").read_text(encoding="utf-8").strip() if (root / "r8-version").is_file() else "?"
        print(f"Dump from R8 {dumped}; replaying with {version}")
        others = sorted(
            path.name for path in root.iterdir()
            if not re.fullmatch(r"(r8-version|build\.properties|proguard\.config|program\.jar|classpath\.jar|"
                                r"library\.jar|desugared-library\.json|main-dex-rules\.txt|"
                                r"(startup|art)-profile.*\.txt)", path.name)
        )
        if others:
            print(f"Not replayed: {', '.join(others)}")
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            # R8's warnings about keep rules and signatures are routine; only a failure is shown.
            print("\n".join((result.stdout + result.stderr).splitlines()[-40:]))
            sys.exit(f"R8 exited with {result.returncode}")
        print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
