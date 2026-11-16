"""Sign into only the disposable loopback fixture through real native UI controls."""
import sys
import time
sys.path.insert(0, 'qa-functional')
import features as ui


def field(label, value, secret=False):
    # Use the visible editor's accessibility action. This avoids unsupported
    # Ctrl+A injection and an IME window replacing the active accessibility tree.
    ui.hide_keyboard()
    def editable():
        anchor = ui.grab(label, 6)
        candidates = [n for n in ui.nodes(ui.snapshot())
                      if n.get('class', '').endswith('EditText')]
        if not candidates:
            raise AssertionError('No editable controls visible: ' + label)
        # Compose may expose the content description on a parent semantics node,
        # separately from the inner EditText; the visible label anchors the row.
        ref = ui.coords(anchor)
        return min(candidates, key=lambda n: abs(ui.coords(n)[1] - ref[1]))

    x, y = ui.coords(editable())
    result = ui.adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'setText',
                    '-e', 'x', x, '-e', 'y', y, '-e', 'value', value, 'qa.yfuse.gesture/.Driver')
    if 'outcome=text-set' not in result:
        raise AssertionError('Visible editor action failed: ' + label)
    time.sleep(.3)
    ui.hide_keyboard()
    if not secret:
        if editable().get('text') != value:
            raise AssertionError('Editable control value was not accepted: ' + label)


def connect():
    ui.pkg = 'com.yfuse'
    ui.launch()
    ui.root('服务器')
    ui.tap(ui.grab(['添加服务器', '添加'], 5))
    ui.wait(['连接到服务器', '服务器地址', '地址'])
    http = ui.find(ui.snapshot(), 'HTTP')
    if http is not None:
        ui.tap(http)
    field('地址', '127.0.0.1')
    field('端口', '18196')
    field('用户名', 'seekqa')
    field('密码', 'local-fixture-only', secret=True)
    ui.tap(ui.grab('连接到服务器', 6))
    ui.wait(['Cloud QA Library', '测试电影', '测试剧集'], 35)
