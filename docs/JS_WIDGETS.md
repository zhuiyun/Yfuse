# JS 组件与自定义首页

入口：手机端「库」顶部的「组件与首页」。安装组件后，新建页面、添加组件模块、选择卡片样式，保存即可。

## 安装与管理

- 在文件管理器或聊天软件中打开 `.js` 文件，选择「Yfuse · 安装 JS 组件」。支持 Android `ACTION_VIEW`、`ACTION_SEND` 和临时 `content://` 读取授权。部分文件管理器未提供打开方式时，可分享给 Yfuse，或在组件中心导入。
- 支持 `application/javascript`、`text/javascript` 等 JS MIME，以及把 JS 标成纯文本、二进制文件的发送方。应用仍检查文件名或明确的 JS MIME，并校验 `WidgetMetadata`。
- 在组件中心输入 HTTP(S) 地址下载安装；也支持 `yfuse://add-widget?url=<经过 URL 编码的地址>`。
- 安装前展示名称、版本、来源和模块。相同组件 ID 再次安装会提示更新，并保留页面栏目、参数及启停状态。卸载保留布局，缺失的组件栏目显示提示。
- 仓库的 [离线示例](../examples/widgets/yfuse-demo.js) 可直接安装。应用内的「体验示例」使用同样的数据。

## 页面与卡片

支持多个页面、重命名、隐藏、排序和默认页面；设为默认时也将启动页切换到「库」。栏目可长按把手排序、用按钮上下移动、复制、复制到其他页面、隐藏、移除、更换组件模块。未保存修改离开前会提醒。

栏目独立保存参数、样式、展示数量、信息显示和紧凑模式；样式选择器提供示例预览，也可加载真实组件数据预览。列表支持刷新、缓存、分页、空数据、错误和缺失组件状态。

| 分组 | 6 种样式 |
| --- | --- |
| 横图信息 | 标准横卡、超宽电影卡、左图右文、右图左文、上图下文、上文下图 |
| 海报展示 | 单排竖卡、双排海报、双列海报网格、三列海报网格、海报详情长卡、叠放片单卡 |
| 焦点推荐 | 沉浸式大图、相邻露出轮播、一大两小组合、主推与列表、杂志拼贴、居中海报轮播 |
| 排行榜 | 大数字横向榜单、紧凑排名列表、前三名领奖台、榜首大卡与列表、分栏分类榜、排名海报网格 |
| 续播追剧 | 横向进度卡、竖向进度卡、分集缩略图列表、下一集焦点卡、每周追剧日历、更新动态时间线 |
| 分类合集 | 方形分类卡、圆形人物卡、标签导航、多海报合集封面、可展开分组列表、简洁文字列表 |

进度、日期、排名分组和人物信息由组件提供；这些展示样式不自动读取或合并媒体服务器的观看记录。无图片、评分或日期时使用占位或省略，不编造真实条目元数据。

## 组件协议

采用 CapyPlayer 常见的 `WidgetMetadata`、`modules.functionName` 和列表函数形式。支持下述接口子集，不承诺任意第三方脚本无需修改即可运行。原生 Compose 负责布局，脚本返回内容，不执行任意 HTML 页面 UI。

```js
var WidgetMetadata = {
  id: "example.movies",
  title: "我的片单",
  version: "1.0.0",
  author: "作者",
  description: "组件介绍",
  modules: [{
    id: "recent",
    title: "最近更新",
    functionName: "loadMovies",
    cacheDuration: 300,
    timeoutSeconds: 30,
    params: [{ name: "page", type: "page" }]
  }]
};

async function loadMovies(params) {
  return [{
    id: "movie-1", title: "影片标题", year: "2026",
    posterPath: "/tmdb-poster-path.jpg",
    description: "内容介绍", mediaType: "movie", type: "tmdb", tmdbId: "123"
  }];
}
```

- 元数据：`id`、`title/name`、`version`、`author`、`description`、`globalParams`、`modules`。
- 模块：`id`、`title/name`、`functionName`（支持 `api.load`）、`type`、`params`、`cacheDuration`（秒）、`timeoutSeconds`（秒）。
- 参数：`name`、`label/title`、`type`、`defaultValue/value`、`required`、`enumOptions/enumValues/placeholders`、`belongTo: {paramName, value}`。支持文本、密码、数字、布尔、选项和自动分页。选项对象使用 `{title, value}`。
- 输入参数的默认值和选项值使用 JSON 基本类型（文本、数字、布尔或 null）；对象、数组会在安装预览时拒绝，避免进入不支持的参数编辑状态。
- 列表函数返回数组。条目支持 `id`、`title/name`、`posterUrl/posterPath`、`backdropUrl/backdropPath`、`description/overview`、`year/releaseDate`、`rating`、`mediaType`、`type`、`tmdbId`、`link`、`videoUrl`、`customHeaders`。兼容对应的常见下划线别名。
- 扩展展示字段：`progress`（0–1）、`date/airDate`、`group/category`、`seasonNumber`、`episodeNumber`。`items`、`seasons`、`episodes`、`episodeItems`、`playSources` 可形成子列表；每层使用不同的 `id`。
- 分类条目带 `params` 时，点击后以这些参数重新加载当前模块。嵌套导航最多五层。
- 详情：条目带 `link` 时调用 `async function loadDetail(link)`，返回详情对象或播放线路数组；子条目支持季集。`videoUrl` 是 HTTP(S) 直链时交给 Yfuse 播放器；纯信息条目可搜索媒体库资源。
- `customHeaders` 仅作用于视频原始同源请求，跨域跳转不转发这些凭据；受保护的组件视频绕过持久播放缓存。

## JS 宿主接口

| 接口 | 行为 |
| --- | --- |
| `Widget.http.get(url, options)` | 返回 `{ok, status, data, headers}`；JSON 自动解析，否则 `data` 为文本 |
| `Widget.http.post(url, body, options)` | JSON 或字符串请求体 |
| `Widget.http.request({url, method, params, headers, data, timeout})` | 支持 GET/POST/PUT/PATCH/DELETE/HEAD，`timeout` 为毫秒 |
| `Widget.tmdb.get('/movie/popular', options)` | 固定 TMDB API 主机，返回解析后的数据；支持 `params` 与可选 `apiKey` |
| `Widget.storage.get(key, defaultValue)` / `getAsync` | 每个组件独立的 JSON 键值存储 |
| `Widget.storage.set(key, value)` / `remove(key)` | 写入或移除组件自身的数据 |
| `Widget.dom.parse(html)` | 返回不透明文档句柄 |
| `Widget.dom.select(node, css)` | 返回元素句柄数组 |
| `Widget.dom.text/html/attr(node, name)` | 读取文本、内部 HTML 或属性；`text/html` 不需要第二参数 |
| `Widget.dom.remove(node)` | 释放句柄 |

`console` 的常见方法可调用但不记录内容，避免脚本将令牌写入应用日志。没有浏览器 DOM、Node.js、Java 反射、任意文件系统或媒体服务器凭据接口。不支持 `fetch`、`require` 和远程 ES module 导入，网络请求使用 `Widget.http`。

## 保存、恢复和边界

- 一个原子 JSON 快照保存脚本、安装信息、页面和栏目；写入失败不发布新的界面状态。损坏数据保留，并提示恢复有效备份。
- 导出备份包含脚本和栏目参数。恢复前验证版本、组件信息和脚本摘要，确认后整体替换；不包含 `Widget.storage` 运行缓存、媒体服务器账号或播放记录。
- 最多 24 个组件、32 个页面、每页 40 个栏目；单脚本 1 MiB、配置快照 24 MiB；单页最多 500 条，季集最多四层。
- 每次调用使用独立 QuickJS 上下文，32 MiB 内存上限；默认 30 秒、最高 60 秒，安装预览 2 秒且禁止网络和写入。离开页面会取消尚未完成的请求。
- 最多三个并行脚本，每次最多 40 次请求、四个并发请求；响应 4 MiB 上限。每组件存储最多 128 个键、约 1 MiB 文本。

验证记录见 `audit/widgets-20261021/`。正式 APK 的版本、签名、MDK、DEX 验证遵循项目 AGENTS.md；本次开发编译与单元测试不生成交付包。
