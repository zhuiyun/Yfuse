"""Actual file browser, channel import and temporary Wi-Fi receiver on cloud guests."""
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from capture import capture, failure_diagnostics
from library_ui import field
from navigation import click
from power import engine, adb, ui


def fixture_server(media):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def send(self, body, mime, status=200):
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            if self.command != 'HEAD':
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        def do_GET(self):
            if self.path == '/list.m3u':
                self.send(b'#EXTM3U\n#EXTINF:-1 group-title="QA",Cloud Channel A\nhttp://127.0.0.1:18198/clip.mp4\n'
                          b'#EXTINF:-1 group-title="QA",Cloud Channel B\nhttp://127.0.0.1:18198/clip.mp4\n', 'audio/x-mpegurl')
            elif self.path.endswith('/clip.mp4'):
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
                if not match:
                    self.send(media, 'video/mp4')
                    return
                start = int(match[1])
                end = min(int(match[2]) if match[2] else len(media)-1, len(media)-1)
                if start > end:
                    self.send(b'', 'text/plain', 416)
                    return
                self.send_response(206)
                self.send_header('Content-Type', 'video/mp4')
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(media)}')
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Length', str(end-start+1))
                self.end_headers()
                if self.command != 'HEAD':
                    self.wfile.write(media[start:end+1])
            else:
                self.send(b'Not Found', 'text/plain', 404)

        do_HEAD = do_GET

        def do_OPTIONS(self):
            self.send_response(200)
            self.send_header('DAV', '1')
            self.send_header('Allow', 'OPTIONS,GET,HEAD,PROPFIND')
            self.send_header('Content-Length', '0')
            self.end_headers()

        def do_PROPFIND(self):
            self.rfile.read(min(int(self.headers.get('Content-Length', '0')), 100000))
            def prop(href, name, folder):
                return ('<D:response><D:href>' + href + '</D:href><D:propstat><D:prop><D:displayname>' + name +
                        '</D:displayname><D:resourcetype>' + ('<D:collection/>' if folder else '') +
                        '</D:resourcetype><D:getcontentlength>' + str(0 if folder else len(media)) +
                        '</D:getcontentlength><D:getcontenttype>' + ('httpd/unix-directory' if folder else 'video/mp4') +
                        '</D:getcontenttype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>')
            body = '<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">'
            if self.path.rstrip('/') == '/dav':
                body += prop('/dav/', 'dav', True)
                if self.headers.get('Depth') != '0':
                    body += prop('/dav/clip.mp4', 'clip.mp4', False)
            elif self.path == '/dav/clip.mp4':
                body += prop('/dav/clip.mp4', 'clip.mp4', False)
            else:
                self.send(b'Not Found', 'text/plain', 404)
                return
            self.send((body + '</D:multistatus>').encode(), 'application/xml', 207)

    server = ThreadingHTTPServer(('127.0.0.1', 18198), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main():
    engine.guard()
    ui.pkg = 'com.yfuse'
    out = Path('results/file-ui')
    out.mkdir(parents=True, exist_ok=True)
    dark = os.environ.get('QA_UI_VARIANT') == 'dark-large'
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3' if dark else '1.0')
    ui.launch()
    ui.root('我的')
    click('外观与交互')
    click('浅色')
    if dark:
        click('深色')
    video = Path(os.environ['RUNNER_TEMP']) / 'ui-import.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=12',
                    '-t', '3', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(video)], check=True)
    media = video.read_bytes()
    server = fixture_server(media)
    adb('reverse', 'tcp:18198', 'tcp:18198')
    rows = []

    def record(name, expected):
        shot = capture(out, name, expected=expected)
        row = {'name': name, 'top': shot, 'status': 'unverified'}
        rows.append(row)
        if 'com.yfuse' not in shot['packages'] or not any(expected in text for text in shot['labels']):
            raise AssertionError('Requested file page missing: ' + name)
        row['status'] = 'captured-needs-review'

    try:
        ui.root('服务器')
        click(['IPTV · 电视直播', 'IPTV・电视直播'])
        field('订阅名称', 'QA Channels')
        field('M3U 地址', 'http://127.0.0.1:18198/list.m3u')
        click('添加订阅')
        record('01-iptv-channels', 'Cloud Channel A')
        field('搜索频道', 'Channel B')
        record('02-iptv-filter', 'Cloud Channel B')
        if any('Cloud Channel A' == value for value in rows[-1]['top']['labels']):
            raise AssertionError('Channel filter retained an unrelated channel')

        ui.launch()
        ui.root('服务器')
        click('Wi-Fi 导入视频')
        click('开启临时接收')
        deadline = time.monotonic() + 25
        link = code = None
        while time.monotonic() < deadline:
            tree = ui.snapshot()
            permission = ui.find(tree, ['允许', 'Allow', 'While using the app', '使用应用时允许'])
            if permission is not None:
                ui.tap(permission)
                continue
            words = ' '.join(word for node in ui.nodes(tree) for word in ui.labels(node))
            link = re.search(r'http://([\d.]+):(\d+)', words)
            code = re.search(r'配对码[：:]\s*(\d+)', words)
            if link and code:
                break
            time.sleep(.3)
        if not link or not code:
            raise AssertionError('Temporary receiver address and pairing code unavailable')
        record('03-wifi-receiving', '停止接收')
        host, port = link.groups()
        request = (f'PUT /upload?name=Imported-QA.mp4 HTTP/1.1\r\nHost: {host}:{port}\r\n'
                   f'Content-Type: video/mp4\r\nX-Yfuse-Pair-Code: {code[1]}\r\nContent-Length: {len(media)}\r\n'
                   'Connection: close\r\n\r\n').encode() + media
        response = subprocess.run(['adb', 'shell', '-T', 'toybox', 'nc', '-w', '12', host, port],
                                  input=request, capture_output=True, timeout=22).stdout
        if re.match(rb'HTTP/1\.[01] 20[01]', response) is None:
            raise AssertionError('Synthetic video import did not receive a success response')
        record('04-wifi-imported', 'Imported-QA.mp4')
        click('停止接收')
        record('05-wifi-stopped', '开启临时接收')

        ui.launch()
        ui.root('服务器')
        click('添加文件来源')
        # WebDAV is already selected; the selected chip intentionally has no
        # click action. Verify the form, then choose the fixture's HTTP scheme.
        ui.wait(['WebDAV', 'WebDav'])
        click('HTTP')
        field('地址', '127.0.0.1')
        field('端口', '18198')
        field('路径', 'dav')
        field('显示名称', 'QA-DAV')
        click('连接并添加')
        record('06-webdav-added', 'QA-DAV')
        ui.root('服务器')
        click('QA-DAV')
        record('07-file-browser', 'clip.mp4')
    except Exception as error:
        rows.append({'name': 'flow-failure', 'status': 'capture-blocked', 'error': type(error).__name__ + ': ' + str(error)[:180]})
        failure_diagnostics(out, 'failure')
        capture(out, 'failure')
        raise
    finally:
        # This is the disposable cloud application only; closing it also closes any temporary receiver.
        adb('shell', 'am', 'force-stop', 'com.yfuse', check=False)
        server.shutdown()
        server.server_close()
        (out / 'coverage.json').write_text(json.dumps({'routes': rows, 'variant': os.environ.get('QA_UI_VARIANT'),
            'auditComplete': False, 'scope': 'Generated3-second media; cloud-only M3U/WebDAV/Wi-Fi UI; not playback qualification'},
            ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
