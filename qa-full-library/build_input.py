from pathlib import Path

driver = Path("qa-source-trace/Driver.java").read_text()
marker = '            if("range".equals(args.getString("mode"))){'
handler = '''            if ("searchtext".equals(args.getString("mode"))) {
                java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
                android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                if (root != null) nodes.add(root);
                boolean changed = false;
                while (!nodes.isEmpty() && !changed) {
                    android.view.accessibility.AccessibilityNodeInfo n = nodes.remove();
                    if (n.isEditable() && "com.yfuse".contentEquals(String.valueOf(n.getPackageName()))) {
                        String value = new String(android.util.Base64.decode(args.getString("query"), 0), "UTF-8");
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
assert driver.count(marker) == 1
Path("qa-volume/input-driver.java").write_text(driver.replace(marker, handler + marker))
