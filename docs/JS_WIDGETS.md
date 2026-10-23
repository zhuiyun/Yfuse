# JS 组件与自定义首页

入口：手机端「库」顶部的「组件与首页」。安装组件后，新建页面、添加组件模块、选择卡片样式，保存即可。

应用已预置 **TMDB** 和 **影视推荐** 两个组件及同名页面，包含 9 个 TMDB 模块和 25 个推荐榜单；保留已有默认首页、页面及个人脚本，卸载后不会在重启时重新安装。来源、在线服务依赖和升级规则见 [预置组件说明](BUNDLED_WIDGETS.md)。

## 安装与管理

- 在文件管理器或聊天软件中打开 `.js` 文件，选择「Yfuse · 安装 JS 组件」。支持 Android `ACTION_VIEW`、`ACTION_SEND` 和临时 `content://` 读取授权。部分文件管理器未提供打开方式时，可分享给 Yfuse，或在组件中心导入。
- 支持 `application/javascript`、`text/javascript` 等 JS MIME，以及把 JS 标成纯文本、二进制文件的发送方。应用仍检查文件名或明确的 JS MIME，并校验 `WidgetMetadata`。
- 在组件中心输入 HTTP(S) 地址下载安装；也支持 `yfuse://add-widget?url=<经过 URL 编码的地址>`。
- 安装前展示名称、版本、来源和模块。相同组件 ID 再次安装会提示更新，并保留页面栏目、参数及启停状态。卸载保留布局，缺失的组件栏目显示提示。
- 仓库的 [离线示例](../examples/widgets/yfuse-demo.js) 可直接安装。应用内的「体验示例」使用同样的数据。

## 页面与卡片

支持多个页面、重命名、隐藏、排序和默认页面；设为默认时也将启动页切换到「库」。栏目可长按把手排序、用按钮上下移动、复制、复制到其他页面、隐藏、移除、更换组件模块。未保存修改离开前会提醒。

栏目独立保存参数、样式、首次展示条目数、信息显示和紧凑模式；样式选择器提供横向示例预览，也可加载真实组件数据预览。内容支持刷新、缓存、分页、空数据、错误和缺失组件状态。

全部 36 种样式统一为**单行横向浏览**；「相邻露出轮播」和「居中海报轮播」使用横向翻页，其余样式左右滑动。每个条目在同一行内独立展示，原来的双排、网格、纵向列表和可展开分组均改为横向卡片。图片、文字和叠放封面仍可在单张卡片内组合，但不会新增第二排内容。

栏目高度由样式、可用宽度、字号及显示选项决定，增加条目不会使栏目向下延伸；标题和简介限制行数，长文字省略显示。标题行的「更多」会显示已加载的其余条目，或在模块支持分页时加载下一页，新增内容仍保留在同一横向行内。编辑器的「首次展示条目数」只控制初始显示数量。

以下 `style` ID 与原有 36 个预设保持兼容。显示名称与布局已更新，旧页面保存的 `double-poster`、`grid-two`、`grid-three`、`expandable` 等 ID 会直接使用对应的新横向样式，无需迁移或重新选择。

| 分组 | `style` ID | 当前名称 | 卡片表现 |
| --- | --- | --- | --- |
| 横图信息 | `landscape` | 标准横卡 | 16:9 横图 · 下方标题 |
| 横图信息 | `cinema` | 超宽电影卡 | 21:9 宽银幕 · 横向浏览 |
| 横图信息 | `side-left` | 左图右文 | 左侧剧照 · 右侧简介 |
| 横图信息 | `side-right` | 右图左文 | 右侧剧照 · 左侧简介 |
| 横图信息 | `image-top` | 上图下文 | 整幅剧照 · 简介卡片横向浏览 |
| 横图信息 | `text-top` | 上文下图 | 信息标题在上 · 图片在下 |
| 海报展示 | `poster` | 单排竖卡 | 2:3 海报 · 横向排列 |
| 海报展示 | `double-poster` | 画框海报 | 轻盈玻璃画框 · 单行横向浏览 |
| 海报展示 | `grid-two` | 大幅海报 | 大尺寸海报 · 单行横向浏览 |
| 海报展示 | `grid-three` | 紧凑海报 | 小尺寸海报 · 单行紧凑陈列 |
| 海报展示 | `poster-detail` | 海报详情长卡 | 海报与简介并列 · 横向浏览 |
| 海报展示 | `poster-stack` | 叠放片单卡 | 倾斜叠放海报 · 片单封面 |
| 焦点推荐 | `hero` | 沉浸式大图 | 背景大图 · 渐变标题 |
| 焦点推荐 | `peek-carousel` | 相邻露出轮播 | 横向翻页 · 预览下一部 |
| 焦点推荐 | `feature-three` | 宽窄焦点卡 | 宽幅主推与窄卡 · 同行横向浏览 |
| 焦点推荐 | `feature-list` | 精选图文卡 | 主推标记 · 图文卡片横向浏览 |
| 焦点推荐 | `magazine` | 杂志长廊 | 宽窄图交替 · 单行编辑精选 |
| 焦点推荐 | `center-poster` | 居中海报轮播 | 聚焦中央海报 · 翻页浏览 |
| 排行榜 | `rank-rail` | 大数字横向榜单 | 编号与海报并列 · 横向滚动 |
| 排行榜 | `rank-list` | 紧凑排名卡 | 名次与缩略图 · 单行信息卡 |
| 排行榜 | `podium` | 荣誉海报榜 | 榜首标记 · 排名海报横向浏览 |
| 排行榜 | `rank-feature` | 榜首焦点卡 | 宽幅榜首 · 后续窄卡同行浏览 |
| 排行榜 | `group-ranks` | 分类排名卡 | 分类与独立编号 · 单行横向榜单 |
| 排行榜 | `rank-grid` | 排名海报长廊 | 海报角标排名 · 单行横向浏览 |
| 续播追剧 | `continue-landscape` | 横向进度卡 | 剧照与播放进度 · 继续观看 |
| 续播追剧 | `continue-poster` | 海报进度卡 | 单行海报 · 标注已看比例 |
| 续播追剧 | `episodes` | 分集图文卡 | 剧照与季集简介 · 横向浏览 |
| 续播追剧 | `next-episode` | 下一集焦点卡 | 突出下一集 · 横滑查看后续剧集 |
| 续播追剧 | `calendar` | 每周追剧日历 | 日期标记 · 每部内容独立横向浏览 |
| 续播追剧 | `timeline` | 横向更新时间线 | 日期节点 · 单行更新记录 |
| 分类合集 | `categories` | 方形分类卡 | 方形封面 · 分类导航 |
| 分类合集 | `people` | 圆形人物卡 | 圆形头像 · 姓名与介绍 |
| 分类合集 | `tags` | 标签导航 | 单行滑动标签 · 轻量入口 |
| 分类合集 | `collection` | 多海报合集封面 | 横向封面拼贴 · 合集入口 |
| 分类合集 | `expandable` | 分组内容卡 | 单行合集入口 · 点击查看内容 |
| 分类合集 | `text-list` | 简洁文字卡 | 标题与评分 · 无图片横向浏览 |

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
- 点击具体影视先进入详情。具有有效正整数 `tmdbId`、`mediaType` 为 `movie`、`tv` 或 `series` 的纯资料条目进入统一 TMDB 详情，返回时回到自定义首页；`type: "tmdb"` 也兼容纯数字 `id`。此路径要求没有 `videoUrl` 和子列表，且 `link` 只能为空或为媒体类型、ID 均匹配的官方 TMDB 资料地址。
- 官方资料地址只接受 HTTP(S) 的 `themoviedb.org` 或 `www.themoviedb.org`，路径为 `/movie/<id>` 或 `/tv/<id>`，可带末尾 `/` 和对应协议的默认端口；带账号信息、查询参数、片段或额外路径的链接仍按来源链接处理。
- 来源详情：带 `videoUrl`、子列表（内部 `children`）或其他来源 `link` 的条目保留组件来源详情，即使同时提供了 `tmdbId`。来源条目带 `link` 时调用 `async function loadDetail(link)`，返回详情对象或播放线路数组；子条目支持季集。详情中的显式播放操作才将 HTTP(S) `videoUrl` 交给 Yfuse 播放器；纯信息条目也可搜索媒体库资源。
- `customHeaders` 仅作用于视频原始同源请求，跨域跳转不转发这些凭据；受保护的组件视频绕过持久播放缓存。

## JS 宿主接口

| 接口 | 行为 |
| --- | --- |
| `Widget.http.get(url, options)` | 返回 `{ok, status, data, headers}`；JSON 自动解析，否则 `data` 为文本 |
| `Widget.http.post(url, body, options)` | JSON 或字符串请求体 |
| `Widget.http.request({url, method, params, headers, data, timeout})` | 支持 GET/POST/PUT/PATCH/DELETE/HEAD，`timeout` 为毫秒 |
| `Widget.tmdb.get('/movie/popular', options)` | 读取公开 TMDB 资料，返回解析后的数据；支持 `params` 与可选 `apiKey`。默认复用 Yfuse 账户代理/直连路由，显式 `apiKey` 使用固定 TMDB API 主机 |
| `Widget.storage.get(key, defaultValue)` / `getAsync` | 每个组件独立的 JSON 键值存储 |
| `Widget.storage.set(key, value)` / `remove(key)` | 写入或移除组件自身的数据 |
| `Widget.dom.parse(html)` | 返回不透明文档句柄 |
| `Widget.dom.select(node, css)` | 返回元素句柄数组 |
| `Widget.dom.text/html/attr(node, name)` | 读取文本、内部 HTML 或属性；`text/html` 不需要第二参数 |
| `Widget.dom.remove(node)` | 释放句柄 |

`console` 的常见方法可调用但不记录内容，避免脚本将令牌写入应用日志。没有浏览器 DOM、Node.js、Java 反射、任意文件系统或媒体服务器凭据接口。不支持 `fetch`、`require` 和远程 ES module 导入，网络请求使用 `Widget.http`。

TMDB 账户路由仅支持公开资料的 GET 相对路径；查询参数不接受账号凭据或嵌套对象。认证由宿主管理，脚本自定义认证头和代理响应头不跨越该接口。第三方推荐榜单使用独立的 `Widget.http`，不会带上 Yfuse 账号认证。

## 保存、恢复和边界

- 一个原子 JSON 快照保存脚本、安装信息、页面和栏目；写入失败不发布新的界面状态。损坏数据保留，并提示恢复有效备份。
- 导出备份包含脚本和栏目参数。恢复前验证版本、组件信息和脚本摘要，确认后整体替换；不包含 `Widget.storage` 运行缓存、媒体服务器账号或播放记录。
- 最多 24 个组件、32 个页面、每页 40 个栏目；单脚本 1 MiB、配置快照 24 MiB；单页最多 500 条，季集最多四层。
- 每次调用使用独立 QuickJS 上下文，32 MiB 内存上限；默认 30 秒、最高 60 秒，安装预览 2 秒且禁止网络和写入。离开页面会取消尚未完成的请求。
- HTML 宿主另设资源上限：单文档 2 MiB、每次调用累计解析 4 MiB、累计 32,000 个 DOM 节点，解析深度最多 256 层；过密标记也会提前拒绝。最多保留 10,000 个句柄，每次选择最多返回 2,000 个元素。释放句柄不返还本次调用的解析额度，新调用获得独立额度。
- 最多三个并行脚本，每次最多 40 次请求、四个并发请求；响应 4 MiB 上限。每组件存储最多 128 个键、约 1 MiB 文本。

验证记录见 `audit/widgets-20261021/`。正式 APK 的版本、签名、MDK、DEX 验证遵循项目 AGENTS.md；本次开发编译与单元测试不生成交付包。
