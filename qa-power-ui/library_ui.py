"""Sign into only the disposable loopback fixture through real native UI controls."""
import sys
sys.path.insert(0, 'qa-functional')
import features as ui


def connect():
    ui.pkg = 'com.yfuse'
    ui.launch()
    ui.root('服务器')
    ui.tap(ui.grab(['添加服务器', '添加'], 5))
    ui.wait(['连接到服务器', '服务器地址', '地址'])
    http = ui.find(ui.snapshot(), 'HTTP')
    if http is not None:
        ui.tap(http)
    ui.field('地址', '127.0.0.1', True)
    ui.field('端口', '18196', True)
    ui.field('用户名', 'seekqa', True)
    ui.field('密码', 'local-fixture-only', True)
    ui.tap(ui.grab('连接到服务器', 6))
    ui.wait(['Cloud QA Library', '测试电影', '测试剧集'], 35)
