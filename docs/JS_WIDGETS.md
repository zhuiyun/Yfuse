# JS 组件与自定义首页

入口：手机端「库」顶部的「组件与首页」。安装组件后，新建页面、添加组件模块、选择卡片样式，保存即可。

应用已预置 **TMDB** 和 **影视推荐** 两个组件及同名页面，包含 9 个 TMDB 模块和 25 个推荐榜单；保留已有默认首页、页面及个人脚本，卸载后不会在重启时重新安装。来源、在线服务依赖和升级规则见 [预置组件说明](BUNDLED_WIDGETS.md)。

组件管理、选择内容来源和首页切换列表中，TMDB、影视推荐依次置顶；其余条目保持原顺序，个人页面内部的栏目顺序和默认首页不变。

## 安装与管理

- 在文件管理器或聊天软件中打开 `.js` 文件，选择「Yfuse · 安装 JS 组件」。支持 Android `ACTION_VIEW`、`ACTION_SEND` 和临时 `content://` 读取授权。部分文件管理器未提供打开方式时，可分享给 Yfuse，或在组件中心导入。
- 支持 `application/javascript`、`text/javascript` 等 JS MIME，以及把 JS 标成纯文本、二进制文件的发送方。应用仍检查文件名或明确的 JS MIME，并校验 `WidgetMetadata`。
- 在组件中心输入 HTTP(S) 地址下载安装；也支持 `yfuse://add-widget?url=<经过 URL 编码的地址>`。
- 安装前展示名称、版本、来源和模块。相同组件 ID 再次安装会提示更新，并保留页面栏目、参数及启停状态。卸载保留布局，缺失的组件栏目显示提示。
- 仓库的 [离线示例](../examples/widgets/yfuse-demo.js) 可直接安装。应用内的「体验示例」使用同样的数据。

## 页面与卡片

支持多个页面、重命名、隐藏、排序和默认页面；设为默认时也将启动页切换到「库」。栏目可长按把手排序、用按钮上下移动、复制、复制到其他页面、隐藏、移除、更换组件模块。未保存修改离开前会提醒。

栏目独立保存参数、样式、首次展示条目数、信息显示和紧凑模式；样式选择器提供横向示例预览，也可加载真实组件数据预览。内容支持刷新、缓存、分页、空数据、错误和缺失组件状态。

同一进程中保留已保存栏目的内容，每个栏目最多 500 条；临时浏览和编辑预览另外使用最近 48 个条目的缓存。滚出屏幕不会取消加载，返回时复用列表，不因缓存到期主动刷新；失败时保留内容并提供显式重试。不同自定义页面分别保存滚动位置。重命名、排序、默认页和样式调整不会清空缓存；修改查询参数、删除栏目及更新、启停、卸载组件会清理对应数据，恢复备份则重新加载全部内容。进程重启后仍需重新请求网络。

全部 63 种样式保持**有界高度、横向浏览**。叠层、折屏、纵深和书脊等支持横向切换；错位双列、海报瀑布和集数矩阵仅在固定高度的卡片或分页内组合，增加影片或集数不会让首页栏目向下延伸。二级页面继续使用多行网格。

栏目高度由样式、可用宽度、字号及显示选项决定，增加条目不会使栏目向下延伸；标题和简介限制行数，长文字省略显示。栏目箭头进入独立二级网格，常规手机宽度与字号下约三列，大字号时自动增大列宽；向下浏览请求后续页，返回保留内容和滚动位置。编辑器的「首次展示条目数」控制首页横向栏目的显示数量。图文卡片仅对图片和玻璃面板裁切圆角，标题与元信息不受整卡圆角遮挡。

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
| 焦点推荐 | `glass-spotlight` | 玻璃聚光 | 全幅剧照 · 悬浮玻璃信息板 |
| 海报展示 | `cinema-ticket` | 影院票根 | 海报与票根信息并列 · 轻盈分隔 |
| 焦点推荐 | `split-focus` | 双景推荐 | 竖海报与横剧照 · 双视角浏览 |
| 海报展示 | `caption-poster` | 悬浮题签 | 大幅竖海报 · 内嵌玻璃题签 |
| 续播追剧 | `date-stamp` | 追剧日签 | 醒目日期 · 剧照与更新信息 |
| 横图信息 | `compact-row` | 轻巧横条 | 精简海报与标题 · 紧凑横向浏览 |
| 空间片廊 | `layered-poster` | 叠层海报 | 中央主海报与两侧叠层 · 滑动切换 |
| 剧集浏览 | `next-direct` | 下一集直达 | 真实观看进度 · 定位下一集 |
| 排行榜 | `big-rank` | 大字榜单 | 醒目名次与真实海报 · 横向浏览 |
| 空间片廊 | `accordion` | 折页片单 | 主封面展开 · 相邻封面轻收拢 |
| 空间片廊 | `arc-poster` | 弧形海报 | 浅弧排列 · 滑动聚焦影片 |
| 构图片廊 | `staggered-columns` | 错位双列 | 有限双列构图 · 横向翻阅 |
| 构图片廊 | `borderless` | 无边光影 | 剧照渐隐融入背景 · 轻盈标题 |
| 剧集浏览 | `season-showcase` | 分季橱窗 | 剧集封面与真实季列表 · 选择查看 |
| 剧集浏览 | `episode-preview` | 分集预览带 | 真实分集剧照 · 集数与观看进度 |
| 剧集浏览 | `episode-matrix` | 集数矩阵 | 分页集数矩阵 · 区分观看状态 |
| 分类合集 | `decade-shelf` | 年代片架 | 按真实上映年代筛选 · 横滑选片 |
| 构图片廊 | `poster-waterfall` | 海报瀑布 | 高低双列封面 · 固定高度横向翻页 |
| 空间片廊 | `book-spine` | 书脊片库 | 紧凑竖向书脊 · 展开选中影片 |
| 构图片廊 | `mirror-poster` | 镜面海报 | 真实封面倒影 · 轻量渐隐 |
| 构图片廊 | `overlap-info` | 透叠信息卡 | 信息跨越剧照下沿 · 玻璃层叠 |
| 分类合集 | `double-feature` | 双片联映 | 两部真实影片成组 · 打开组合或单片 |
| 构图片廊 | `floating-poster` | 悬边海报 | 竖封面轻悬底板 · 右侧片名与评分 |
| 构图片廊 | `staggered-rail` | 错层横廊 | 高低海报交替 · 底边与文字对齐 |
| 构图片廊 | `banner-stack` | 横幅叠片 | 同片横幅与竖海报交叠 · 稳定下沿 |
| 空间片廊 | `depth-gallery` | 纵深片廊 | 前后递进的海报 · 滑动进入前景 |
| 空间片廊 | `folding-gallery` | 折屏画廊 | 连续折面展开 · 透视随手势过渡 |

普通样式使用组件提供的真实字段，无图片、评分或日期时使用占位或省略。新增剧集样式支持懒加载本机已配置媒体库的真实季集，并通过宿主投影个人观看状态；脚本不能接触服务器凭据或自行指定原生媒体库跳转目标。无法匹配时保留原始影视资料和重试入口，不生成假季集或假进度。编辑器预览不触发媒体服务器请求。

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
- 扩展展示字段：`progress`（0–1）、`date/airDate`、`group/category`、`seasonNumber/season`、`episodeNumber/episode`。`items`、`seasons`、`episodes`、`episodeItems`、`playSources`、`childItems` 可形成子列表；每层使用不同的 `id`。
- 分类条目带 `params` 时，点击后以这些参数重新加载当前模块。嵌套导航最多五层。
- 影视条目（`mediaType` 为 `movie`、`tv` 或 `series`）统一进入应用默认详情流程，返回时回到原栏目或二级列表。有效正整数 `tmdbId` 直接用于匹配；`type: "tmdb"` 也兼容纯数字 `id`。纯资料条目匹配到媒体服务器已有资源后，在同一个详情路由中显示完整媒体库详情，不自动播放；各服务器结果逐步显示，失败可显式重试。带组件播放来源的条目保留组件来源区。
- 附带 `videoUrl`、子列表或来源 `link` 不改变影视详情入口。未匹配到媒体库时，资料页保留组件来源；自定义来源的 `link` 调用 `loadDetail`，标准 TMDB 资料链接不重复调用。分集与线路横向排列，继承父级请求头、封面和集数信息，只有明确点击来源才播放。
- 无有效 TMDB 编号的内容直接打开默认详情框架，影视按片名、类型及年份在页内匹配。仅唯一的精确匹配补充 TMDB 资料；重名、近似或无匹配结果保留组件原始资料和播放来源，不再跳转匹配中间页。普通来源的数字 `id`、URL 不会被猜测为 TMDB 编号。未声明媒体类型的 `url/live/video` 条目按视频处理，直播不查询影视库。
- 非影视内容仍兼容来源详情：带 `link` 时调用 `async function loadDetail(link)`，返回详情对象或播放线路数组；子条目支持季集。显式播放操作才将 HTTP(S) `videoUrl` 交给 Yfuse 播放器。
- `customHeaders` 仅作用于视频原始同源请求，跨域跳转不转发这些凭据；受保护的组件视频绕过持久播放缓存。
- 播放头也兼容 `headers`，与 `customHeaders` 同时提供时以后者为准。分集只有 `link` 时点击播放再解析。
- 声明 `id: "loadResource", type: "stream"` 的模块在点击播放时调用，参数保留栏目设置，并附加内容标识、链接和季集信息。返回带 `url/videoUrl` 的线路数组；此模块不出现在首页栏目选择中。参见 [Forward 播放资源模块协议](https://github.com/InchStudio/ForwardWidgets/blob/master/README.md)。
- 栏目右侧仅保留箭头，进入独立列表后继续分页。返回、滚动离屏和缓存时间到期不触发自动刷新，失败后可显式重试；重试绕过之前失败请求的缓存。组件来源详情在进程内保留，显式重试可重新请求；直播或视频来源在点击播放时重新解析链接，以获取当前播放地址。
- `type: "url"` 的 HTTP(S) `id` 可作为缺省 `link`。当脚本未提供可选的 `loadDetail` 时，带 `.m3u8`、`.mpd`、`.flv` 等已知媒体扩展名的链接可直接播放；普通网页链接不直接交给播放器。内部控制头 `X-Forward-Skip-Redirect-Probe` 不作为 HTTP 请求头转发给视频站点。

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
- 组件安装数量、页面数量与每页栏目数量不设固定上限；仍保留单脚本 1 MiB、配置快照 24 MiB 的体积保护，以及单次响应最多 500 条、季集最多四层的运行安全限制。
- 每次调用使用独立 QuickJS 上下文，32 MiB 内存上限；默认 30 秒、最高 60 秒，安装预览 2 秒且禁止网络和写入。首页列表请求由共享数据源管理，滚动离屏或打开详情不取消；组件被修改、停用或移除时相应数据源失效。
- HTML 宿主另设资源上限：单文档 2 MiB、每次调用累计解析 4 MiB、累计 32,000 个 DOM 节点，解析深度最多 256 层；过密标记也会提前拒绝。最多保留 10,000 个句柄，每次选择最多返回 2,000 个元素。释放句柄不返还本次调用的解析额度，新调用获得独立额度。
- 最多三个并行脚本，每次最多 40 次请求、四个并发请求；响应 4 MiB 上限。每组件存储最多 128 个键、约 1 MiB 文本。

验证记录见 `audit/widgets-20261021/`。正式 APK 的版本、签名、MDK、DEX 验证遵循项目 AGENTS.md；本次开发编译与单元测试不生成交付包。
