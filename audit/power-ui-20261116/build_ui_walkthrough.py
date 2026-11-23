"""Build the offline Chinese route-by-route report from captured Android evidence."""
import argparse
import html
import json
import os
from pathlib import Path
from urllib.parse import quote


ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT.parent / 'legacy-engine-20261113' / 'power-ui-20261116'
LABELS = {
    'servers': '服务器首页', 'library-empty': '空媒体库', 'watching': '观看', 'profile': '我的',
    'history': '观看记录', 'about-section': '关于与版本', 'saved-server': '已有一台服务器',
    'populated-library': '已连接的媒体库', 'discover': '发现', 'movie-detail': '电影详情',
    'long-title-detail': '长标题电影详情', 'series-detail': '剧集详情与分集卡片',
    'movie-library': '电影列表', 'series-library': '剧集列表', 'diagnostics': '数据与诊断',
}


def route_name(row):
    if row['name'] in LABELS:
        return LABELS[row['name']]
    value = row['route'][-1] if row.get('route') else row['tab']
    return value[0] if isinstance(value, list) else value


def local_link(file, out):
    if not file.is_file():
        raise FileNotFoundError(file)
    return quote(Path(os.path.relpath(file, out)).as_posix(), safe='/._-')


def state_text(row, issue):
    if row['status'] == 'capture-blocked':
        return '此步骤的交互检查未通过。' + issue
    if row['name'] == 'diagnostics':
        return '已到达。备份工具启用 Android 屏幕保护时，截图不能用于核对受保护区域的外观。'
    if row.get('motionDialog', {}).get('dismissed'):
        return '已看到最后一项，实测点击关闭后返回动效设置。' + issue
    if row.get('fixtureLabelsVerified'):
        return ('已核对首两集各只显示一次集数。' if row['name'] == 'series-detail'
                else '已核对当前服务器及添加文件来源入口，单服务器时空分组已收起。')
    if row.get('scrollEndObserved'):
        return '已到达并确认滚动终点。' + issue
    return '已到达，滚动终点尚待确认。' + issue


def build(round_no):
    out = ROOT / f'ui-review-{round_no}'
    root = EVIDENCE / f'full-ui-{round_no}'
    index = json.loads((out / 'index.json').read_text('utf8'))
    destination_file = ROOT / f'ui-destinations-{round_no}.json'
    unverified = {(m['group'], m['route']) for m in json.loads(destination_file.read_text('utf8'))['mismatches']} if destination_file.is_file() else set()
    seen = {(x['group'], x['route'], Path(x['file']).name) for x in index['captures']}
    variants = {'light': '浅色 · 标准字号', 'dark-large': '深色 · 130% 字号'}
    by_route = {}
    for scope in ('primary', 'secondary'):
        for variant in variants:
            group = f'{scope}-{variant}'
            folder = root / ('ui-verified-' + group) / 'ui'
            data = json.loads((folder / 'coverage.json').read_text('utf8'))
            for row in data['routes']:
                key = row['name']
                shots = []
                if row.get('posterTipBeforeDismiss'):
                    shots.append(('首次使用海报提示', row['posterTipBeforeDismiss']))
                if 'top' in row:
                    shots.append(('入口画面', row['top']))
                shots.extend((f'滚动位置 {pos}', shot) for pos, shot in enumerate(row.get('scrolls', []), 1))
                if row.get('blockedState'):
                    shots.append(('检查失败时的画面', row['blockedState']))
                figures = []
                for label, shot in shots:
                    assert (group, key, shot['image']) in seen
                    image = local_link(folder / shot['image'], out)
                    tree = local_link(folder / shot['tree'], out)
                    figures.append(f'<figure><a href="{image}"><img loading="lazy" src="{image}" alt="{html.escape(route_name(row))} · {label}"></a>'
                                   f'<figcaption>{label} · <a href="{tree}">Android 控件树</a></figcaption></figure>')
                issue = ('滚动后关闭按钮的无障碍触控区域缩小，详见 UI-030。'
                         if key == 'particle-choice' and 'Dialog header moved' in str(row.get('reason','')) else '')
                text = html.escape(state_text(row, issue))
                if (group, key) in unverified:
                    text = '截取的页面与预期目的地不同，已从该轮的通过数中扣除。下方保留原图及控件树。'
                if row['status'] == 'capture-blocked' and not issue:
                    text += ' ' + html.escape(row.get('reason', ''))
                title = html.escape(variants[variant]); count = len(shots)
                part = f'<div class="variant"><h3>{title}</h3><p>{text}</p><details><summary>查看 {count} 张截图</summary><div class="shots">{"".join(figures)}</div></details></div>'
                by_route.setdefault(key, {'name': route_name(row), 'tab': row['tab'], 'variants': []})['variants'].append(part)
    assert all(len(r['variants']) == len(variants) for r in by_route.values())
    sections = []
    for j, route in enumerate(by_route.values(), 1):
        name, tab = html.escape(route['name']), html.escape(str(route['tab']))
        sections.append(f'<section><h2>{j}. {name}</h2><small>{tab}</small><div class="variants">{"".join(route["variants"])}</div></section>')
    body = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Yfuse 逐页界面复核 · 第 {round_no} 轮</title><style>
*{{box-sizing:border-box}}body{{margin:0;background:#f4f7fa;font:16px/1.55 system-ui,"Microsoft YaHei",sans-serif;color:#1b2939}}main{{max-width:1100px;margin:auto;padding:32px 18px}}section{{padding:20px 0;border-top:1px solid #c9d4df}}h1{{font-size:28px}}h2{{font-size:20px;margin:0}}h3{{font-size:16px;margin:0}}small,figcaption{{color:#465c70}}.variants{{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:12px}}.variant{{background:#fff;border:1px solid #d9e2eb;border-radius:8px;padding:15px;min-width:0}}summary,a{{color:#174e91}}summary{{cursor:pointer}}.shots{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}}figure{{margin:12px 0 0}}img{{width:100%;height:auto;vertical-align:middle}}figcaption{{font-size:12px}}code{{overflow-wrap:anywhere}}@media(max-width:650px){{.variants{{grid-template-columns:1fr}}}}
</style><main><a href="../report.html">功耗与界面报告</a><h1>逐页界面复核 · 第 {round_no} 轮</h1>
<p>{len(by_route)} 条页面路径，两种显示配置。截图与控件树来自同一次云端操作，点击图片可查看原始尺寸。每项的说明对应实际完成的操作。</p>
<p>源码 <code>{html.escape(index['source'])}</code> · <a href="https://github.com/zhuiyun/Yfuse/actions/runs/{int(index['run'])}">云端运行</a></p>{''.join(sections)}</main></html>'''
    (out/'overview.html').write_text(body,encoding='utf8')
    print(f'{len(by_route)} routes, {sum(len(r["variants"]) for r in by_route.values())} variants')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('round', type=int)
    build(parser.parse_args().round)
