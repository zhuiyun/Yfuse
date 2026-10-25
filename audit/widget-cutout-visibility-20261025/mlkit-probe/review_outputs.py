"""Evaluate real downloaded ML Kit masks with the already-compiled production gate.

Run only after the probe produced both real outputs. This does not run or fake ML Kit.
The classpath is supplied explicitly so the reviewed source revision stays traceable.
"""
from pathlib import Path
from PIL import Image
import argparse
import hashlib
import json
import struct
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    args = parser.parse_args()
    report = json.loads((args.directory / "report.json").read_text())
    if report["status"] != "segmentation_outputs_created" or len(report["cases"]) != 2:
        raise SystemExit("No complete real model outputs; segmentation quality is unknown")
    lines = []
    for case in report["cases"]:
        name = case["name"]
        data = (args.directory / f"{name}.f32").read_bytes()
        if hashlib.sha256(data).hexdigest() != case["mask_sha256"]:
            raise SystemExit("Changed real mask bytes")
        quality = args.directory / f"{name}-clamped.f32"
        if quality.exists():
            data = quality.read_bytes()
            if hashlib.sha256(data).hexdigest() != case["quality_mask_sha256"]:
                raise SystemExit("Changed finite saturated confidence bytes")
        if len(data) != case["width"] * case["height"] * 4:
            raise SystemExit("Wrong mask size")
        mask = Image.frombytes("F", (case["width"], case["height"]), data, "raw", "F;32F")
        mask = mask.resize((320, 320), Image.Resampling.BILINEAR)
        values = list(mask.getdata())
        target = args.directory / f"{name}-gate320.f32"
        target.write_bytes(struct.pack("<" + "f" * len(values), *values))
        lines.append("\t".join((name, str(target.resolve()), str(case["width"]), str(case["height"]))))
    result = subprocess.run([args.java, "-cp", args.classpath, "com.yfuse.core.widget.InspectMasksKt"],
                            input="\n".join(lines) + "\n", text=True, capture_output=True, check=True)
    reviews = [json.loads(line) for line in result.stdout.splitlines()]
    if [case["name"] for case in reviews] != [case["name"] for case in report["cases"]]:
        raise SystemExit("Mismatched quality results")
    output = {"probe_report": report, "production_gate": reviews,
              "note": "Gate acceptance is not semantic ground truth; inspect the original PNG foregrounds"}
    (args.directory / "quality-review.json").write_text(json.dumps(output, indent=2) + "\n")
    print(json.dumps(reviews, indent=2))


if __name__ == "__main__":
    main()
