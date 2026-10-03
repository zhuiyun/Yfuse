#!/usr/bin/env python3
"""Show why ART's verifier rejected one method of a release APK.

Disassembles the class with baksmali's verifier model (the register types ART's verifier also
tracks), prints the instruction at the rejected offset, and traces every definition of the named
register that reaches it. Also summarises the largest methods of the class and describes the
classes named with --describe, so the obfuscated types in the rejection can be identified.

Example (the 1.0.97 player crash):
  inspect_dex_method.py --baksmali-classpath "$CP" --apk Yfuse-259-1.0.97.apk \
      --class 'Lcom/yfuse/feature/player/PlayerRootKt;' --method 'PlayerRoot$lambda$152' \
      --offset 0x23EB --register v1 --describe 'Ldv7;'
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

OFFSET = re.compile(r"^\s*#@([0-9a-f]+)\s*$")
LABEL = re.compile(r"^\s*(:[\w]+)\s*$")
# baksmali names every label after the code offset it marks: :cond_4d, :try_end_27, :pswitch_data_1a0.
LABEL_OFFSET = re.compile(r"_([0-9a-f]+)$")
REGISTER_TYPES = re.compile(r"(v\d+)=(\([^)]*\))(?::merge\{([^}]*)\})?")
BRANCH_TARGET = re.compile(r"(:[\w]+)\s*$")
CATCH = re.compile(r"^\s*\.catch(?:all)?\s.*\{(:\w+) \.\. (:\w+)\}\s+(:\w+)\s*$")
FIRST_REGISTER = re.compile(r"^[\w/-]+\s+\{?v(\d+)")
UNCONDITIONAL_END = ("goto", "return", "throw")
# Instructions that leave every register as it was; all others write their first register.
WRITES_NOTHING = re.compile(
    r"^(return|monitor-|throw|goto|packed-switch|sparse-switch|if-|aput|iput|sput|invoke-|"
    r"filled-new-array|fill-array-data|nop)"
)
WRITES_PAIR = re.compile(
    r"^(move-wide|move-result-wide|const-wide|iget-wide|sget-wide|aget-wide|(neg|not)-(long|double)|"
    r"(int|float|long|double)-to-(long|double)|(add|sub|mul|div|rem|and|or|xor|shl|shr|ushr)-(long|double))"
)


@dataclass
class Block:
    offset: int
    labels: list[str] = field(default_factory=list)
    before: list[str] = field(default_factory=list)
    instruction: str = ""
    after: list[str] = field(default_factory=list)

    def types(self, lines: list[str]) -> dict[str, tuple[str, str]]:
        found: dict[str, tuple[str, str]] = {}
        for line in lines:
            for register, kind, merge in REGISTER_TYPES.findall(line):
                found[register] = (kind, merge)
        return found

    @property
    def opcode(self) -> str:
        return self.instruction.split()[0] if self.instruction else ""


def run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stdout}{result.stderr}")


def extract_dex(apk: Path, into: Path) -> list[Path]:
    with zipfile.ZipFile(apk) as archive:
        names = sorted(
            (name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)),
            key=lambda name: int(re.sub(r"\D", "", name) or "1"),
        )
        for name in names:
            archive.extract(name, into)
    return [into / name for name in names]


def disassemble(classpath: str, dex_files: list[Path], descriptor: str, out: Path) -> Path:
    smali_name = descriptor[1:-1] + ".smali"
    for dex in dex_files:
        others = ":".join(str(other) for other in dex_files if other != dex)
        target = out / dex.stem
        command = [
            "java", "-Xmx6g", "-cp", classpath, "org.jf.baksmali.Main", "d",
            "-r", "ARGS,DEST,MERGE,FULLMERGE", "--code-offsets",
            "--parameter-registers", "false", "--bootclasspath", "",
            "--classes", descriptor, "-o", str(target), str(dex),
        ]
        if others:
            command[command.index("--classes"):command.index("--classes")] = ["--classpath", others]
        run(command)
        candidate = target / smali_name
        if candidate.is_file():
            print(f"{descriptor} is in {dex.name}")
            return candidate
    sys.exit(f"{descriptor} is not in any dex of the APK")


def methods(smali: Path) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    current: list[str] | None = None
    for line in smali.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith(".method "):
            current = [line]
            found[line] = current
        elif current is not None:
            current.append(line)
            if line.startswith(".end method"):
                current = None
    return found


def parse_blocks(body: list[str]) -> list[Block]:
    blocks: list[Block] = []
    block: Block | None = None
    for line in body:
        offset = OFFSET.match(line)
        if offset:
            block = Block(int(offset.group(1), 16))
            blocks.append(block)
            continue
        if block is None:
            continue
        stripped = line.strip()
        if not stripped or stripped.startswith(".line") or stripped.startswith(".local") or stripped.startswith(".end local") or stripped.startswith(".restart"):
            continue
        if LABEL.match(line):
            block.labels.append(stripped)
        elif stripped.startswith("#v") or stripped.startswith("#p"):
            (block.after if block.instruction else block.before).append(stripped)
        elif stripped.startswith(".catch") or stripped.startswith(".end method"):
            block.after.append(stripped)
        elif not stripped.startswith("#") and not block.instruction:
            block.instruction = stripped
        elif not stripped.startswith("#"):
            # Payload directives (.packed-switch data and the like) follow a label of their own.
            block.after.append(stripped)
    return blocks


def signature_parameters(method_line: str) -> int:
    descriptor = method_line[method_line.index("(") + 1:method_line.index(")")]
    return len(re.findall(r"\[*(?:L[^;]+;|[ZBSCIJFD])", descriptor))


def registers(body: list[str]) -> str:
    for line in body:
        if line.strip().startswith(".registers"):
            return line.split()[1]
    return "?"


def predecessors(blocks: list[Block], body: list[str]) -> dict[int, list[int]]:
    """Control-flow predecessors by offset, with every instruction of a try range feeding its handler."""
    by_label = label_offsets(body)
    switch_targets: dict[str, list[str]] = {}
    current_payload: str | None = None
    pending_labels: list[str] = []
    for line in body:
        stripped = line.strip()
        if LABEL.match(line):
            pending_labels.append(stripped)
            continue
        if stripped.startswith(".packed-switch") or stripped.startswith(".sparse-switch"):
            current_payload = pending_labels[-1] if pending_labels else None
            switch_targets.setdefault(current_payload or "", [])
        elif stripped.startswith(".end packed-switch") or stripped.startswith(".end sparse-switch"):
            current_payload = None
        elif current_payload is not None:
            target = re.search(r"(:\w+)\s*$", stripped)
            if target:
                switch_targets[current_payload].append(target.group(1))
        if not stripped.startswith("#") and stripped:
            pending_labels = []
    preds: dict[int, list[int]] = {block.offset: [] for block in blocks}
    for index, block in enumerate(blocks):
        opcode = block.opcode
        if not opcode or opcode.startswith("."):
            continue
        if not opcode.startswith(UNCONDITIONAL_END) and index + 1 < len(blocks):
            preds[blocks[index + 1].offset].append(block.offset)
        if opcode.startswith(("goto", "if-")):
            target = BRANCH_TARGET.search(block.instruction)
            if target and target.group(1) in by_label:
                preds[by_label[target.group(1)]].append(block.offset)
        if opcode in ("packed-switch", "sparse-switch"):
            payload = BRANCH_TARGET.search(block.instruction)
            for label in switch_targets.get(payload.group(1) if payload else "", []):
                if label in by_label:
                    preds[by_label[label]].append(block.offset)
    for line in body:
        catch = CATCH.match(line)
        if not catch:
            continue
        start, end, handler = (by_label.get(label) for label in catch.groups())
        if start is None or end is None or handler is None:
            continue
        for block in blocks:
            if start <= block.offset < end and block.opcode and not block.opcode.startswith("."):
                preds[handler].append(block.offset)
    return preds


def label_offsets(body: list[str]) -> dict[str, int]:
    offsets: dict[str, int] = {}
    for line in body:
        label = LABEL.match(line)
        if label:
            number = LABEL_OFFSET.search(label.group(1))
            if number:
                offsets[label.group(1)] = int(number.group(1), 16)
    return offsets


def writes(block: Block, register: str) -> bool:
    """Whether the instruction leaves a new value (or, for <init>, a newly initialised one) in the register."""
    instruction = block.instruction
    if not instruction or instruction.startswith(".") or (
        WRITES_NOTHING.match(instruction) and not (instruction.startswith("invoke-direct") and "<init>" in instruction)
    ):
        return False
    first = FIRST_REGISTER.match(instruction)
    if not first:
        return False
    written = {f"v{first.group(1)}"}
    if WRITES_PAIR.match(instruction):
        written.add(f"v{int(first.group(1)) + 1}")
    return register in written


def trace(blocks: list[Block], preds: dict[int, list[int]], start: int, register: str) -> list[int]:
    """Offsets of every instruction whose value of the register reaches the start instruction."""
    seen: set[int] = set()
    definitions: list[int] = []
    queue = deque(preds.get(start, []))
    by_offset = {block.offset: block for block in blocks}
    while queue:
        offset = queue.popleft()
        if offset in seen:
            continue
        seen.add(offset)
        block = by_offset[offset]
        if writes(block, register):
            definitions.append(offset)
            continue
        queue.extend(preds.get(offset, []))
    return sorted(definitions)


def show(block: Block, register: str | None = None) -> str:
    before = block.types(block.before)
    after = block.types(block.after)
    note = []
    if register:
        if register in before:
            kind, merge = before[register]
            note.append(f"{register} in {kind}" + (f" merged from {{{merge}}}" if merge else ""))
        if register in after:
            note.append(f"{register} out {after[register][0]}")
    labels = " ".join(block.labels)
    text = f"  @{block.offset:04x} {labels + ' ' if labels else ''}{block.instruction[:220]}"
    return text + (f"    | {'; '.join(note)}" if note else "")


def describe(classpath: str, dex_files: list[Path], descriptor: str, out: Path) -> None:
    smali = disassemble(classpath, dex_files, descriptor, out / "describe")
    print(f"== {descriptor}")
    for line in smali.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith((".class", ".super", ".implements", ".source", ".field", ".method")):
            print("  " + line[:300])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--baksmali-classpath", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--class", dest="descriptor", required=True)
    parser.add_argument("--method", help="method name to inspect; omit for the summary only")
    parser.add_argument("--offset", type=lambda value: int(value, 16))
    parser.add_argument("--register", default="v1")
    parser.add_argument("--context", type=int, default=40, help="instructions shown before the rejected one")
    parser.add_argument("--describe", action="append", default=[])
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as work:
        work_dir = Path(work)
        dex_files = extract_dex(args.apk, work_dir / "dex")
        print(f"{args.apk.name}: {len(dex_files)} dex files")
        smali = disassemble(args.baksmali_classpath, dex_files, args.descriptor, work_dir / "smali")
        all_methods = methods(smali)

        print(f"== Largest methods of {args.descriptor} (parameters, registers, code units)")
        sizes = []
        for line, body in all_methods.items():
            blocks = parse_blocks(body)
            size = blocks[-1].offset if blocks else 0
            sizes.append((size, signature_parameters(line), registers(body), line))
        for size, parameters, register_count, line in sorted(sizes, reverse=True)[:8]:
            name = line.split("(")[0].split()[-1]
            print(f"  {name}: {parameters} parameters, {register_count} registers, ~{size} code units")

        if args.method:
            matches = [(line, body) for line, body in all_methods.items() if line.split("(")[0].split()[-1] == args.method]
            if not matches:
                sys.exit(f"{args.method} is not a method of {args.descriptor}")
            line, body = matches[0]
            blocks = parse_blocks(body)
            print(f"== {args.method}: {signature_parameters(line)} parameters, {registers(body)} registers")
            if args.offset is not None:
                index = next((i for i, block in enumerate(blocks) if block.offset == args.offset), None)
                if index is None:
                    sys.exit(f"no instruction at offset 0x{args.offset:x}")
                target = blocks[index]
                print("== Rejected instruction and the types its operands carry")
                print(show(target, args.register))
                for entry in target.before:
                    print(f"      {entry[:400]}")
                print(f"== {args.context} instructions before it")
                for block in blocks[max(0, index - args.context):index + 3]:
                    print(show(block, args.register))
                preds = predecessors(blocks, body)
                definitions = trace(blocks, preds, target.offset, args.register)
                print(f"== Definitions of {args.register} that reach @{target.offset:04x}: {len(definitions)}")
                by_offset = {block.offset: i for i, block in enumerate(blocks)}
                for offset in definitions[:12]:
                    position = by_offset[offset]
                    print(f"-- @{offset:04x}")
                    for block in blocks[max(0, position - 6):position + 2]:
                        print(show(block, args.register))
        for descriptor in args.describe:
            describe(args.baksmali_classpath, dex_files, descriptor, work_dir)


if __name__ == "__main__":
    main()
