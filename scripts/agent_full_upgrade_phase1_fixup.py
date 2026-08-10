from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/QrScannerActivity.kt"
text = path.read_text(encoding="utf-8")

replacements = {
    'addView(torch, LinearLayout.LayoutParams(0, 48.dp, 1f))': 'addView(this@QrScannerActivity.torch, LinearLayout.LayoutParams(0, 48.dp, 1f))',
    'override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray)': 'override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray)',
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"missing CameraX fixup pattern: {old}")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("phase1 CameraX fixup applied")
