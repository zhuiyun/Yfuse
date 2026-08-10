from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/QrScannerActivity.kt"
text = path.read_text(encoding="utf-8")

# Use Activity Result for camera permission and explicit View receivers throughout. This keeps
# the scanner free of the legacy permission callback and Kotlin receiver ambiguity in apply{}.
needle = '    private val galleryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n'
insert = '''    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            hint.text = "相机权限被拒绝，可在系统设置开启，或直接从相册识别"
            AppLog.warning(
                category = "server.migration",
                event = "scanner_permission_denied",
                message = "QR scanner camera permission was denied",
            )
        }
    }

'''
if needle not in text:
    raise SystemExit("missing gallery picker anchor")
text = text.replace(needle, insert + needle, 1)

replacements = {
    'requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)': 'cameraPermission.launch(Manifest.permission.CAMERA)',
    'addView(torch, LinearLayout.LayoutParams(0, 48.dp, 1f))': 'this.addView(this@QrScannerActivity.torch, LinearLayout.LayoutParams(0, 48.dp, 1f))',
    'addView(gallery, LinearLayout.LayoutParams(0, 48.dp, 1f))': 'this.addView(gallery, LinearLayout.LayoutParams(0, 48.dp, 1f))',
    'addView(hint, LinearLayout.LayoutParams(-1, 54.dp))': 'this.addView(this@QrScannerActivity.hint, LinearLayout.LayoutParams(-1, 54.dp))',
    'addView(actions, LinearLayout.LayoutParams(-1, 48.dp))': 'this.addView(actions, LinearLayout.LayoutParams(-1, 48.dp))',
    'addView(previewView, FrameLayout.LayoutParams(-1, -1))': 'this.addView(this@QrScannerActivity.previewView, FrameLayout.LayoutParams(-1, -1))',
    'addView(overlay, FrameLayout.LayoutParams(-1, -1))': 'this.addView(this@QrScannerActivity.overlay, FrameLayout.LayoutParams(-1, -1))',
    'addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))': 'this.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))',
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"missing CameraX fixup pattern: {old}")
    text = text.replace(old, new, 1)

start = text.find('    override fun onRequestPermissionsResult(')
if start < 0:
    raise SystemExit("missing legacy permission callback")
end = text.find('    private fun finishWithResult', start)
if end < 0:
    raise SystemExit("missing finishWithResult anchor")
text = text[:start] + text[end:]
text = text.replace('        private const val REQUEST_CAMERA = 8401\n', '')

path.write_text(text, encoding="utf-8")
print("phase1 CameraX fixup applied")
