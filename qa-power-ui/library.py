"""Loopback-only synthetic Emby API for populated UI and polling observations.

This is a UI fixture, not evidence of compatibility with an actual Emby release.
The token is a disposable fixture value, never an account credential.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
from urllib.parse import parse_qs, urlparse


def item(key, title, kind='Movie', **extra):
    return {'Id': key, 'Name': title, 'Type': kind, 'ProductionYear': 2026,
            'Overview': '本地界面测试内容，用于检查长标题、海报、操作层级和空状态。',
            'Genres': ['剧情', '测试'], 'RunTimeTicks': 36_000_000_000,
            'CommunityRating': 8.1, 'ImageTags': {'Primary': 'fixture'},
            'BackdropImageTags': ['fixture'],
            'UserData': {'Played': False, 'IsFavorite': False, 'PlaybackPositionTicks': 0}, **extra}


class LibraryServer:
    def __init__(self, output, port=18196, name='Cloud QA Library'):
        self.output = Path(output)
        self.lock = threading.Lock()
        self.requests = 0
        self.failure = False
        self.name = name
        self.server_id = 'cloud-qa-only' if port == 18196 else 'cloud-qa-' + str(port)
        self.movies = [item('movie-' + str(i), title) for i, title in enumerate([
            '山海之间', '星际来信', '雨后小镇',
            '这是一个用于检验换行与省略行为的很长电影标题：夜空下的旅程'])]
        self.series = item('series-1', '城市的一天', 'Series')
        self.season = item('season-1', '第一季', 'Season', SeriesId='series-1', IndexNumber=1)
        self.episodes = [item('episode-' + str(i), '第 %d 集 · 清晨之后' % i, 'Episode',
                             SeriesId='series-1', SeriesName='城市的一天', SeasonId='season-1',
                             ParentIndexNumber=1, IndexNumber=i) for i in range(1, 5)]
        self.items = {r['Id']: r for r in self.movies + [self.series, self.season] + self.episodes}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.serve()

            def do_HEAD(self):
                self.serve()

            def do_POST(self):
                length = min(int(self.headers.get('Content-Length', '0')), 1_000_000)
                if length:
                    self.rfile.read(length)
                self.serve()

            def do_DELETE(self):
                self.serve()

            def serve(self):
                parsed = urlparse(self.path)
                path = parsed.path.removeprefix('/emby')
                query = parse_qs(parsed.query)
                parts = path.strip('/').split('/')
                media = (self.command in ('GET', 'HEAD') and len(parts) >= 3
                         and parts[0].lower() == 'videos' and parts[1] in owner.items
                         and parts[2].split('.')[0].lower() in ('stream', 'original'))
                status = 503 if owner.failure else 307 if media else 200
                with owner.lock, owner.output.open('a', encoding='utf-8') as log:
                    owner.requests += 1
                    # No headers, query parameters, credentials or user text are logged.
                    log.write(json.dumps({'time': time.time(), 'method': self.command,
                                          'route': path, 'status': status}) + '\n')
                if owner.failure:
                    self.send_error(503)
                    return
                if media:
                    self.send_response(307)
                    self.send_header('Location', 'http://127.0.0.1:18389/fixture.mp4')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                if '/Images/' in path:
                    asset = Path('composeApp/src/androidMain/assets/widget-preview/lake.webp')
                    self.reply(asset.read_bytes(), 'image/webp')
                    return
                if path.endswith('/System/Info/Public') or path.endswith('/System/Info'):
                    value = {'ServerName': owner.name, 'Version': '4.8.0.0',
                             'Id': owner.server_id, 'ProductName': 'Emby Server'}
                elif path.endswith('/Users/AuthenticateByName'):
                    value = {'AccessToken': 'local-fixture-only', 'User': {'Id': 'qa', 'Name': 'seekqa'}}
                elif path.endswith('/Users/Public'):
                    value = [{'Id': 'qa', 'Name': 'seekqa', 'HasPassword': True}]
                elif path.endswith('/Views'):
                    value = {'Items': [{'Id': 'movies', 'Name': '测试电影', 'CollectionType': 'movies'},
                                       {'Id': 'series', 'Name': '测试剧集', 'CollectionType': 'tvshows'}]}
                elif path.endswith('/Items/Counts'):
                    value = {'MovieCount': len(owner.movies), 'SeriesCount': 1}
                elif path.endswith('/PlaybackInfo'):
                    key = path.split('/')[-2]
                    value = {'PlaySessionId': 'qa-play', 'MediaSources': [owner.source(key)]}
                elif path.endswith('/Items/Latest'):
                    value = owner.select(query)
                elif path.split('/')[-1] in owner.items:
                    key = path.split('/')[-1]
                    value = dict(owner.items[key], MediaSources=[owner.source(key)])
                elif path.endswith('/Seasons'):
                    value = {'Items': [owner.season], 'TotalRecordCount': 1}
                elif path.endswith('/Episodes') or path.endswith('/NextUp'):
                    value = {'Items': owner.episodes, 'TotalRecordCount': len(owner.episodes)}
                elif path.endswith('/Items') or path.endswith('/Items/Resume'):
                    rows = owner.select(query)
                    value = {'Items': rows, 'TotalRecordCount': len(rows)}
                elif path.endswith('/Filters') or path.endswith('/Filters2'):
                    value = {'Genres': ['剧情'], 'Years': [2026]}
                elif self.command != 'GET':
                    value = {}
                else:
                    value = {'Items': [], 'TotalRecordCount': 0}
                self.reply(json.dumps(value, ensure_ascii=False).encode(), 'application/json')

            def reply(self, data, mime):
                self.send_response(200)
                self.send_header('Content-Type', mime)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                try:
                    if self.command != 'HEAD':
                        self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def select(self, query):
        parent = query.get('ParentId', [''])[0]
        kind = query.get('IncludeItemTypes', [''])[0]
        rows = ([self.series] if parent == 'series' or kind == 'Series' else
                self.episodes if parent == 'season-1' or kind == 'Episode' else self.movies)
        if 'Ids' in query:
            rows = [self.items[key] for key in query['Ids'][0].split(',') if key in self.items]
        term = query.get('SearchTerm', [''])[0].casefold()
        return [r for r in rows if term in r['Name'].casefold()]

    def source(self, key):
        return {'Id': key, 'Container': 'mp4', 'Path': 'http://127.0.0.1:18389/fixture.mp4',
                'Protocol': 'Http', 'RunTimeTicks': 36_000_000_000,
                'SupportsDirectPlay': True, 'SupportsDirectStream': True,
                'DirectStreamUrl': 'http://127.0.0.1:18389/fixture.mp4',
                'MediaStreams': [{'Index': 0, 'Type': 'Video', 'Codec': 'h264', 'Width': 640, 'Height': 360,
                                  'AspectRatio': '16:9', 'VideoRange': 'SDR', 'BitDepth': 8},
                                 {'Index': 1, 'Type': 'Audio', 'Codec': 'aac', 'Channels': 1}]}

    def close(self):
        self.server.shutdown()
        self.server.server_close()
