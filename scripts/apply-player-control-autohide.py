from pathlib import Path

path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt")
text = path.read_text()

old_key = """        state.playing,\n        interactions,\n        accessibilityManager,\n        controlsHaveFocus,\n"""
new_key = """        state.playing || (state.buffering && state.positionMs > 0L),\n        interactions,\n        accessibilityManager,\n        controlsHaveFocus,\n"""
if old_key not in text:
    raise SystemExit("auto-hide effect key not found")
text = text.replace(old_key, new_key, 1)

old_guard = """        if (\n            !visible ||\n            !state.playing ||\n            overlayOpen ||\n            controlsHaveFocus\n        ) {\n            return@LaunchedEffect\n        }\n"""
new_guard = """        // Keep the hide timer stable across NativeDirect's playing <-> buffering handoff.\n        // A remote Range stall is still an active playback request, not a user pause.\n        // During initial startup we wait until playback has actually advanced or rendered.\n        val playbackActive = state.playing || (state.buffering && state.positionMs > 0L)\n        if (\n            !visible ||\n            !playbackActive ||\n            overlayOpen ||\n            controlsHaveFocus\n        ) {\n            return@LaunchedEffect\n        }\n"""
if old_guard not in text:
    raise SystemExit("auto-hide guard not found")
text = text.replace(old_guard, new_guard, 1)
path.write_text(text)
print("player control auto-hide patch applied")
