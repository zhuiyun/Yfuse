#!/usr/bin/env python3
"""Keep Kotlin functions from growing without bound.

PlayerRoot grew from about 3,000 to 3,700 lines in three weeks, and at that size R8 9.1 turned its
content lambda into a DEX that Android 17 refused to verify, so the release crashed before its
first frame. This gate measures every function body in production Kotlin and fails when

  * a function not listed in the baseline is longer than MAX_LINES, or
  * a listed function is longer than its recorded ceiling.

Shrinking is always allowed; run with --update after a split to lower the ceilings (never to raise
them without a reason in the commit message).

Usage: scripts/check_function_size.py [--update] [--baseline config/function-size-baseline.txt]
"""
import argparse
import os
import re
import subprocess
import sys

MAX_LINES = 1_500
ROOTS = ("composeApp/src", "tvApp/src", "watchTogetherServer/src/main", "watchTogetherProtocol/src")
EXCLUDED_PARTS = ("Test/", "/test/", "androidInstrumentedTest", "/build/")
FUNCTION = re.compile(
    r"^[ \t]*(?:@[\w.]+(?:\([^)]*\))?[ \t]+)*"
    r"(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec|"
    r"external|actual|expect|open|abstract|final)[ \t]+)*"
    r"fun[ \t]+(?:<[^>]*>[ \t]+)?(?:[\w.<>?, ]+\.)?(`?[\w]+`?)[ \t]*[(<]",
    re.M,
)


# After a signature, a blank line or the start of another declaration means there was no body.
DECLARATION_END = re.compile(
    r"[ \t]*(?:\n|}|@|fun\b|val\b|var\b|class\b|object\b|interface\b|override\b|private\b|"
    r"internal\b|public\b|protected\b|suspend\b|abstract\b|open\b|data\b|enum\b|sealed\b|companion\b)"
)


def strip_literals(text):
    """Blank out comments, strings and char literals, keeping newlines, so braces can be counted."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif text.startswith('"""', i) or c == '"':
            raw = text.startswith('"""', i)
            quote = '"""' if raw else '"'
            j = i + len(quote)
            buf = [" " * len(quote)]
            depth = 0
            while j < n:
                if not raw and text[j] == "\\":
                    buf.append("  ")
                    j += 2
                    continue
                if depth == 0 and text.startswith(quote, j):
                    buf.append(" " * len(quote))
                    j += len(quote)
                    break
                if text.startswith("${", j):
                    depth += 1
                    buf.append("  ")
                    j += 2
                    continue
                if depth and text[j] == "{":
                    depth += 1
                elif depth and text[j] == "}":
                    depth -= 1
                buf.append("\n" if text[j] == "\n" else " ")
                j += 1
            out.append("".join(buf))
            i = j
        elif c == "'" and i + 2 < n and (text[i + 2] == "'" or (text[i + 1] == "\\" and "'" in text[i + 2 : i + 8])):
            j = text.find("'", i + 2 if text[i + 1] != "\\" else i + 3) + 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def function_lengths(path):
    text = open(path, encoding="utf-8").read()
    clean = strip_literals(text)
    results = []
    for match in FUNCTION.finditer(clean):
        name = match.group(1).strip("`")
        # The body is the first '{' at parenthesis depth 0 after the name; an '=' there first
        # means an expression body, which is never long enough to matter here.
        i = match.end() - 1
        depth = 0
        params_closed = False
        while i < len(clean):
            ch = clean[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                params_closed = params_closed or depth == 0
            elif depth == 0 and ch in "{=":
                break
            elif ch == "\n" and params_closed and DECLARATION_END.match(clean, i + 1):
                # An abstract or interface member: the next line starts something else.
                i = len(clean)
                break
            i += 1
        if i >= len(clean) or clean[i] != "{":
            continue
        braces = 0
        while i < len(clean):
            if clean[i] == "{":
                braces += 1
            elif clean[i] == "}":
                braces -= 1
                if braces == 0:
                    break
            i += 1
        first = clean.count("\n", 0, match.start()) + 1
        last = clean.count("\n", 0, i) + 1
        results.append((name, first, last - first + 1))
    return results


def production_files(repo):
    files = subprocess.run(["git", "ls-files", *ROOTS], cwd=repo, capture_output=True, text=True, check=True)
    for rel in files.stdout.split():
        if rel.endswith(".kt") and not any(part in rel for part in EXCLUDED_PARTS):
            yield rel


def load_baseline(path):
    ceilings = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#"):
                key, lines = line.rsplit(" ", 1)
                ceilings[key] = int(lines)
    return ceilings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--baseline", default="config/function-size-baseline.txt")
    args = parser.parse_args()
    repo = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip()
    baseline_path = os.path.join(repo, args.baseline)
    ceilings = load_baseline(baseline_path)
    measured = {}
    for rel in production_files(repo):
        for name, line, length in function_lengths(os.path.join(repo, rel)):
            if length > MAX_LINES or f"{rel}::{name}" in ceilings:
                key = f"{rel}::{name}"
                measured[key] = max(measured.get(key, 0), length)
                print(f"{length:6d}  {rel}:{line}  {name}")
    if args.update:
        with open(baseline_path, "w", encoding="utf-8") as out:
            out.write(
                "# Functions over the size gate, with the most lines each may have "
                "(scripts/check_function_size.py).\n"
            )
            out.write("# Lower a ceiling after a split; raise one only with a reason in the commit.\n")
            for key in sorted(measured):
                out.write(f"{key} {measured[key]}\n")
        print(f"baseline written: {len(measured)} entries")
        return 0
    failures = []
    for key, length in sorted(measured.items()):
        ceiling = ceilings.get(key)
        if ceiling is None and length > MAX_LINES:
            failures.append(f"{key}: {length} lines, over the {MAX_LINES}-line limit for new functions")
        elif ceiling is not None and length > ceiling:
            failures.append(f"{key}: {length} lines, over its recorded ceiling of {ceiling}")
    if failures:
        print("\nFunction size gate failed:\n  " + "\n  ".join(failures))
        print("Split the function rather than raising the limit.")
        return 1
    print("function size gate: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
