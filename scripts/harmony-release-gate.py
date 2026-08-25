#!/usr/bin/env python3
"""Fail-closed preflight for producing a signed HarmonyOS Release HAP."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def check(label: str, condition: bool, detail: str) -> bool:
    print(("PASS" if condition else "BLOCK") + f" {label}: {detail}")
    return condition


def main() -> int:
    subprocess.run([sys.executable, str(ROOT / "scripts/verify-harmony-port.py")], check=True)
    ok = True
    cjc = shutil.which("cjc")
    cjpm = shutil.which("cjpm")
    hvigor = shutil.which("hvigorw") or shutil.which("hvigor")
    sdk_root = os.environ.get("DEVECO_CANGJIE_HOME", "")
    ok &= check("Cangjie compiler", cjc is not None, cjc or "cjc not found")
    ok &= check("Cangjie package manager", cjpm is not None, cjpm or "cjpm not found")
    ok &= check("Hvigor", hvigor is not None, hvigor or "hvigor not found")
    ok &= check("Harmony Cangjie SDK", bool(sdk_root) and Path(sdk_root).is_dir(), sdk_root or "DEVECO_CANGJIE_HOME not set")

    build_profile = (ROOT / "harmonyApp/build-profile.json5").read_text(encoding="utf-8")
    signed = '"signingConfigs": []' not in build_profile
    ok &= check("Release signing", signed, "production signing config required outside source control")

    gates = json.loads((ROOT / "parity/capability-gates.json").read_text(encoding="utf-8"))["gates"]
    unresolved = [gate["id"] for gate in gates if gate["status"] != "verified"]
    ok &= check("Capability evidence", not unresolved, ", ".join(unresolved) if unresolved else "all verified")

    coverage = json.loads((ROOT / "parity/implementation-coverage.json").read_text(encoding="utf-8"))["features"]
    missing_adapters = [feature["id"] for feature in coverage if feature["status"] == "sdkAdapter"]
    ok &= check(
        "Platform adapters",
        not missing_adapters,
        ", ".join(missing_adapters) if missing_adapters else "all implemented",
    )

    if not ok:
        print("Release HAP generation is blocked; debug/source validation remains valid.", file=sys.stderr)
        return 2

    print("Release preflight passed. Run the pinned DevEco release HAP task.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f"BLOCK release preflight failed: {error}", file=sys.stderr)
        sys.exit(2)
