#!/usr/bin/env python3
"""Recompile an R8 input dump with another R8 release.

AGP's R8 writes its complete input (program classes, feature splits, resources, libraries, merged
keep rules and settings) as a zip when the JVM runs with
-Dcom.android.tools.r8.dumpinputtodirectory=<existing dir>. Replaying that dump with a given
r8lib.jar shows what exactly that R8 version would put in the APK, so a compiler defect can be
checked against newer releases without changing the build. Replaying with the R8 that wrote the
dump must reproduce AGP's output; check that first.

usage: replay_r8_dump.py --dump dump.zip --r8 r8lib.jar --output-dir out
Writes out/base.zip and, for each feature split in the dump, out/feature-N.zip.
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
    parser.add_argument("--output-dir", required=True, type=Path, help="directory for base.zip and feature-N.zip")
    parser.add_argument("--heap", default="4g")
    parser.add_argument("--locate", action="append", default=[], help="class (a/b/C) to find among the dump's jars")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as work:
        root = Path(work)
        with zipfile.ZipFile(args.dump) as dump:
            dump.extractall(root)
        build = properties((root / "build.properties").read_text(encoding="utf-8"))
        if build.get("tool") != "R8":
            sys.exit(f"{args.dump} is a {build.get('tool')} dump, not R8")
        out = args.output_dir
        out.mkdir(parents=True, exist_ok=True)
        print("Dump entries:")
        with zipfile.ZipFile(args.dump) as dump:
            for info in dump.infolist():
                classes = ""
                if info.filename.endswith(".jar"):
                    with zipfile.ZipFile(root / info.filename) as jar:
                        classes = f", {sum(1 for name in jar.namelist() if name.endswith('.class'))} classes"
                print(f"  {info.filename}: {info.file_size} bytes{classes}")
                if info.filename.endswith(".jar"):
                    with zipfile.ZipFile(root / info.filename) as jar:
                        names = set(jar.namelist())
                    for wanted in args.locate:
                        if f"{wanted}.class" in names:
                            print(f"    contains {wanted}")
        launcher = Path(__file__).with_name("ReplayR8.java")
        command = ["java", f"-Xmx{args.heap}", "-cp", str(args.r8), str(launcher)]
        if build.get("enable-missing-library-api-modeling") == "true":
            command.append("--missing-library-api-modeling")
        command += [
            "--release" if build.get("mode", "release") == "release" else "--debug",
            "--min-api", build["min-api"],
            "--lib", str(root / "library.jar"),
            "--pg-conf", str(root / "proguard.config"),
            "--output", str(out / "base.zip"),
        ]
        if (root / "app-res.ap_").is_file():
            command += ["--android-resources", str(root / "app-res.ap_"), str(out / "base-res.ap_")]
        features = sorted({re.sub(r"\.(jar|ap_)$", "", path.name) for path in root.glob("feature-*.*")})
        for feature in features:
            classes, resources = root / f"{feature}.jar", root / f"{feature}.ap_"
            source = (str(classes) if classes.is_file() else "") + (f":{resources}" if resources.is_file() else "")
            target = (str(out / f"{feature}.zip") if classes.is_file() else "") + (
                f":{out / f'{feature}-res.ap_'}" if resources.is_file() else "")
            command += ["--feature", source, target]
        if build.get("isolated-splits") == "true":
            command.append("--isolated-splits")
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
                                r"library\.jar|desugared-library\.json|main-dex-rules\.txt|app-res\.ap_|"
                                r"feature-\d+\.(jar|ap_)|(startup|art)-profile.*\.txt)", path.name)
        )
        if others:
            print(f"Not replayed: {', '.join(others)}")
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            # R8's warnings about keep rules and signatures are routine; only a failure is shown.
            print("\n".join((result.stdout + result.stderr).splitlines()[-40:]))
            sys.exit(f"R8 exited with {result.returncode}")
        print(f"Wrote {', '.join(sorted(path.name for path in out.iterdir()))}")


if __name__ == "__main__":
    main()
