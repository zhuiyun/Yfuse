"""Sign into only the disposable loopback fixture through real native UI controls."""
import sys
import shlex
import time
from pathlib import Path
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
    command = ['am', 'instrument', '-w', '-e', 'mode', 'setText',
               '-e', 'x', str(x), '-e', 'y', str(y), '-e', 'value', value, 'qa.yfuse.gesture/.Driver']
    # adb shell joins arguments into a remote shell command. Quote the whole
    # command so spaces, Unicode and URL punctuation remain one input value.
    result = ui.adb('shell', shlex.join(command))
    if 'outcome=text-set' not in result:
        evidence = Path('results/form-input-failure.txt')
        evidence.write_text(result.replace(value, '<fixture-value>'), encoding='utf-8')
        raise AssertionError('Visible editor action failed: ' + label)
    time.sleep(.3)
    ui.hide_keyboard()
    if not secret:
        # A placeholder disappears after typing. Verify the same visible editor
        # by its previous position instead of searching for the vanished label.
        candidates = [n for n in ui.nodes(ui.snapshot())
                      if n.get('class', '').endswith('EditText') and n.get('visible') != 'false']
        actual = min(candidates, key=lambda n: abs(ui.coords(n)[0] - x) + abs(ui.coords(n)[1] - y), default=None)
        if actual is None or actual.get('text') != value:
            raise AssertionError('Editable control value was not accepted: ' + label)


def submit_search(value):
    editors = [n for n in ui.nodes(ui.snapshot())
               if n.get('class', '').endswith('EditText') and n.get('text') == value]
    if len(editors) != 1:
        raise AssertionError('Expected one populated search editor')
    ui.tap(editors[0])
    x, y = ui.coords(editors[0])
    command = ['am', 'instrument', '-w', '-e', 'mode', 'imeAction',
               '-e', 'x', str(x), '-e', 'y', str(y), 'qa.yfuse.gesture/.Driver']
    result = ui.adb('shell', shlex.join(command))
    if 'outcome=ime-submitted' not in result:
        raise AssertionError('Search IME submission was not accepted')
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        editors = [n for n in ui.nodes(ui.snapshot())
                   if n.get('class', '').endswith('EditText') and n.get('text') == value]
        if len(editors) == 1 and editors[0].get('focused') == 'false':
            return
        time.sleep(.25)
    raise AssertionError('Search editor retained focus after IME submission')


def connect(port=18196, relaunch=True):
    ui.pkg = 'com.yfuse'
    if relaunch:
        ui.launch()
    ui.root('服务器')
    ui.tap(ui.grab(['添加服务器', '添加'], 5))
    ui.wait(['连接到服务器', '服务器地址', '地址'])
    http = ui.find(ui.snapshot(), 'HTTP')
    if http is not None:
        ui.tap(http)
    field('地址', '127.0.0.1')
    field('端口', str(port))
    field('用户名', 'seekqa')
    field('密码', 'local-fixture-only', secret=True)
    ui.tap(ui.grab('连接到服务器', 6))
    ui.wait(['Cloud QA Library', '测试电影', '测试剧集'], 35)
