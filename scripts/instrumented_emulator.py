"""Prepare the disposable instrumentation emulator and capture the real tablet settings UI."""
import argparse
import json
from pathlib import Path

from android_cloud_ui import Session
from release_metadata import read_release


class ValidationSession(Session):
    ui_package = "com.yfuse.validation"


def tablet_settings(session, apk):
    if session.adb("shell", "pm", "list", "packages", session.ui_package).strip():
        raise RuntimeError("Tablet preflight requires a clean validation app installation")
    result = session.adb("install", "--no-streaming", str(apk), timeout=180)
    if "Success" not in result:
        raise RuntimeError(f"Validation APK installation failed: {result}")
    try:
        for setting, value in [("accelerometer_rotation", "0"), ("user_rotation", "1"), ("font_scale", "1.0")]:
            session.adb("shell", "settings", "put", "system", setting, value)
        session.adb("shell", "cmd", "uimode", "night", "no")
        session.adb("shell", "wm", "density", "240")
        session.adb("shell", "wm", "size", "1200x1800")
        session.launch()
        session.tap("我的")
        session.tap("浅色")
        frames = []
        for name, size, width, paired in [
            ("settings-1200dp", "1200x1800", 1800, True),
            ("settings-700dp", "930x1050", 1050, False),
            ("settings-1200dp-restored", "1200x1800", 1800, True),
        ]:
            session.adb("shell", "wm", "size", size)
            frame = session.settings_layout(name, paired, index_label="搜索设置", detail_label="视觉效果")
            if frame["width"] != width:
                raise RuntimeError(f"{name}: unexpected screenshot width {frame['width']}")
            frames.append(frame)
        (session.output / "tablet-settings.json").write_text(json.dumps(frames, ensure_ascii=False, indent=2))
    finally:
        session.restore()
        session.adb("uninstall", session.ui_package)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tablet-apk", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    session = ValidationSession(args.output, read_release(Path(__file__).resolve().parents[1]),
                                "instrumented", expected_api=args.api)
    session.connect()
    print(session.wait_for_boot_load(), flush=True)
    if args.tablet_apk:
        tablet_settings(session, args.tablet_apk)


if __name__ == "__main__":
    main()
