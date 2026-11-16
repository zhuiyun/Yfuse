from pathlib import Path
import runpy

runpy.run_path('qa-full-library/build_input.py')
path = Path('qa-volume/input-driver.java')
source = path.read_text()
helper = '''    private int editableCount(android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return 0;
        int count = n.isEditable() ? 1 : 0;
        for (int i = 0; i < n.getChildCount(); i++) count += editableCount(n.getChild(i));
        return count;
    }
    private boolean hasFieldLabel(android.view.accessibility.AccessibilityNodeInfo n, String label) {
        if (n == null) return false;
        String desc = String.valueOf(n.getContentDescription());
        String text = String.valueOf(n.getText());
        if (label.equals(text) || desc.contains(label)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (hasFieldLabel(n.getChild(i), label)) return true;
        return false;
    }
    private boolean matchesField(android.view.accessibility.AccessibilityNodeInfo n, String label) {
        android.view.accessibility.AccessibilityNodeInfo current = n;
        for (int level = 0; current != null && level < 4; level++, current = current.getParent()) {
            if (editableCount(current) == 1 && hasFieldLabel(current, label)) return true;
        }
        return false;
    }
'''
assert '    private String playbackState(' in source
source = source.replace('    private String playbackState(', helper + '    private String playbackState(', 1)
marker = '            if("range".equals(args.getString("mode"))){'
handler = '''            if ("loginfield".equals(args.getString("mode"))) {
                String label = new String(android.util.Base64.decode(args.getString("label"), 0), "UTF-8");
                android.os.ParcelFileDescriptor fd = ui.executeShellCommand("cat /data/local/tmp/yfuse-qa-login-field");
                String value;
                try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd), "UTF-8"))) { value = in.readLine(); }
                java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
                android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                if (root != null) nodes.add(root);
                boolean changed = false;
                while (!nodes.isEmpty() && !changed) {
                    android.view.accessibility.AccessibilityNodeInfo n = nodes.remove();
                    if (n.isEditable() && "com.yfuse".contentEquals(String.valueOf(n.getPackageName())) && matchesField(n, label)) {
                        Bundle b = new Bundle();
                        b.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                        changed = n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                    }
                    for (int j = 0; j < n.getChildCount(); j++) {
                        android.view.accessibility.AccessibilityNodeInfo child = n.getChild(j);
                        if (child != null) nodes.add(child);
                    }
                }
                result.putString("changed", Boolean.toString(changed));
                finish(-1, result);
                return;
            }
'''
assert source.count(marker) == 1
path.write_text(source.replace(marker, handler + marker))
