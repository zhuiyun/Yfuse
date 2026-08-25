#!/usr/bin/env python3
"""SDK-independent structural checks for Cangjie sources."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "harmonyApp/entry/src/main/cangjie/src"


def stripped(source: str) -> str:
    output: list[str] = []
    index = 0
    state = "code"
    while index < len(source):
        char = source[index]
        pair = source[index:index + 2]
        if state == "code":
            if pair == "//": state = "line"; output.extend("  "); index += 2; continue
            if pair == "/*": state = "block"; output.extend("  "); index += 2; continue
            if char == '"': state = "string"; output.append(" "); index += 1; continue
            output.append(char); index += 1; continue
        if state == "line":
            if char == "\n": state = "code"; output.append("\n")
            else: output.append(" ")
            index += 1; continue
        if state == "block":
            if pair == "*/": state = "code"; output.extend("  "); index += 2
            else: output.append("\n" if char == "\n" else " "); index += 1
            continue
        if state == "string":
            if char == "\\" and index + 1 < len(source): output.extend("  "); index += 2
            elif char == '"': state = "code"; output.append(" "); index += 1
            else: output.append("\n" if char == "\n" else " "); index += 1
    if state in {"string", "block"}:
        raise AssertionError(f"unterminated {state}")
    return "".join(output)


def validate_file(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    if not source.startswith("package "):
        raise AssertionError(f"missing package declaration: {path.relative_to(ROOT)}")
    if "TODO" in source or "FIXME" in source:
        raise AssertionError(f"unfinished marker: {path.relative_to(ROOT)}")
    clean = stripped(source)
    pairs = {"(": ")", "[": "]", "{": "}"}
    stack: list[tuple[str, int]] = []
    for index, char in enumerate(clean):
        if char in pairs: stack.append((char, index))
        elif char in pairs.values():
            if not stack or pairs[stack[-1][0]] != char:
                raise AssertionError(f"unbalanced delimiter: {path.relative_to(ROOT)}:{index}")
            stack.pop()
    if stack:
        raise AssertionError(f"unclosed delimiter: {path.relative_to(ROOT)}:{stack[-1][1]}")


def main() -> int:
    files = sorted(SOURCE_ROOT.rglob("*.cj"))
    if len(files) < 40:
        raise AssertionError("unexpectedly incomplete Cangjie source tree")
    for path in files:
        validate_file(path)

    ui = "\n".join(path.read_text(encoding="utf-8") for path in (SOURCE_ROOT / "ui").glob("*.cj"))
    expected = {
        "SplashScreen", "HomeScreen", "TmdbInfoScreen", "CalendarScreen", "LibraryScreen",
        "LibraryGridScreen", "DetailScreen", "SeasonEpisodesScreen", "ServersScreen",
        "ServerEditorScreen", "SearchScreen", "ProfileScreen", "DownloadsScreen",
        "AccountSettingsScreen", "AccountSessionsScreen", "MediaDiscoverySettingsScreen", "PlayerScreen",
    }
    missing = sorted(name for name in expected if f"class {name}" not in ui)
    if missing:
        raise AssertionError(f"missing screen components: {missing}")

    entry = (SOURCE_ROOT / "entry_view.cj").read_text(encoding="utf-8")
    routed_screens = expected - {"SplashScreen", "HomeScreen", "LibraryScreen", "ServersScreen", "SearchScreen", "ProfileScreen"}
    unrouted = sorted(name for name in routed_screens if f"{name}(" not in entry)
    if unrouted:
        raise AssertionError(f"screen exists but is not routed from EntryView: {unrouted}")
    retained_stacks = {"homeRoute", "libraryRoute", "serversRoute", "searchRoute", "profileRoute"}
    missing_stacks = sorted(name for name in retained_stacks if f"@State var {name}:" not in entry)
    if missing_stacks:
        raise AssertionError(f"root tab does not retain nested route: {missing_stacks}")
    if "if (selectedTab == index) { popToRoot() }" not in entry:
        raise AssertionError("active-tab reselection must unwind the active nested route")

    foreign_files = [path.relative_to(SOURCE_ROOT).as_posix() for path in files if "foreign {" in path.read_text(encoding="utf-8")]
    allowed_foreign = {"player/ycore_ffi.cj", "player/native_capabilities.cj", "storage/secure_store.cj"}
    if not set(foreign_files).issubset(allowed_foreign):
        raise AssertionError(f"FFI escaped boundary: {foreign_files}")

    ffi = (SOURCE_ROOT / "player/ycore_ffi.cj").read_text(encoding="utf-8")
    for symbol in ("ycore_session_open_values", "ycore_session_handover", "ycore_session_state_reason"):
        if f"func {symbol}(" not in ffi:
            raise AssertionError(f"missing YCore FFI declaration: {symbol}")

    print(f"PASS Cangjie structural checks ({len(files)} files)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, OSError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
