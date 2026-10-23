package com.yfuse.core.widget

/** IDs are persistent; display labels and ordering can evolve without changing a saved page. */
enum class WidgetStyle(
    val id: String,
    val title: String,
    val group: String,
    val description: String,
) {
    Landscape("landscape", "标准横卡", "横图信息", "16:9 横图 · 下方标题"),
    Cinema("cinema", "超宽电影卡", "横图信息", "21:9 宽银幕 · 横向浏览"),
    SideLeft("side-left", "左图右文", "横图信息", "左侧剧照 · 右侧简介"),
    SideRight("side-right", "右图左文", "横图信息", "右侧剧照 · 左侧简介"),
    ImageTop("image-top", "上图下文", "横图信息", "整幅剧照 · 简介卡片横向浏览"),
    TextTop("text-top", "上文下图", "横图信息", "信息标题在上 · 图片在下"),
    Poster("poster", "单排竖卡", "海报展示", "2:3 海报 · 横向排列"),
    DoublePoster("double-poster", "画框海报", "海报展示", "轻盈玻璃画框 · 单行横向浏览"),
    GridTwo("grid-two", "大幅海报", "海报展示", "大尺寸海报 · 单行横向浏览"),
    GridThree("grid-three", "紧凑海报", "海报展示", "小尺寸海报 · 单行紧凑陈列"),
    PosterDetail("poster-detail", "海报详情长卡", "海报展示", "海报与简介并列 · 横向浏览"),
    PosterStack("poster-stack", "叠放片单卡", "海报展示", "倾斜叠放海报 · 片单封面"),
    Hero("hero", "沉浸式大图", "焦点推荐", "背景大图 · 渐变标题"),
    PeekCarousel("peek-carousel", "相邻露出轮播", "焦点推荐", "横向翻页 · 预览下一部"),
    FeatureThree("feature-three", "宽窄焦点卡", "焦点推荐", "宽幅主推与窄卡 · 同行横向浏览"),
    FeatureList("feature-list", "精选图文卡", "焦点推荐", "主推标记 · 图文卡片横向浏览"),
    Magazine("magazine", "杂志长廊", "焦点推荐", "宽窄图交替 · 单行编辑精选"),
    CenterPoster("center-poster", "居中海报轮播", "焦点推荐", "聚焦中央海报 · 翻页浏览"),
    RankRail("rank-rail", "大数字横向榜单", "排行榜", "编号与海报并列 · 横向滚动"),
    RankList("rank-list", "紧凑排名卡", "排行榜", "名次与缩略图 · 单行信息卡"),
    Podium("podium", "荣誉海报榜", "排行榜", "榜首标记 · 排名海报横向浏览"),
    RankFeature("rank-feature", "榜首焦点卡", "排行榜", "宽幅榜首 · 后续窄卡同行浏览"),
    GroupRanks("group-ranks", "分类排名卡", "排行榜", "分类与独立编号 · 单行横向榜单"),
    RankGrid("rank-grid", "排名海报长廊", "排行榜", "海报角标排名 · 单行横向浏览"),
    ContinueLandscape("continue-landscape", "横向进度卡", "续播追剧", "剧照与播放进度 · 继续观看"),
    ContinuePoster("continue-poster", "海报进度卡", "续播追剧", "单行海报 · 标注已看比例"),
    Episodes("episodes", "分集图文卡", "续播追剧", "剧照与季集简介 · 横向浏览"),
    NextEpisode("next-episode", "下一集焦点卡", "续播追剧", "突出下一集 · 横滑查看后续剧集"),
    Calendar("calendar", "每周追剧日历", "续播追剧", "日期标记 · 每部内容独立横向浏览"),
    Timeline("timeline", "横向更新时间线", "续播追剧", "日期节点 · 单行更新记录"),
    Categories("categories", "方形分类卡", "分类合集", "方形封面 · 分类导航"),
    People("people", "圆形人物卡", "分类合集", "圆形头像 · 姓名与介绍"),
    Tags("tags", "标签导航", "分类合集", "单行滑动标签 · 轻量入口"),
    Collection("collection", "多海报合集封面", "分类合集", "横向封面拼贴 · 合集入口"),
    Expandable("expandable", "分组内容卡", "分类合集", "单行合集入口 · 点击查看内容"),
    TextList("text-list", "简洁文字卡", "分类合集", "标题与评分 · 无图片横向浏览"),
    ;

    companion object {
        fun fromId(id: String): WidgetStyle = entries.firstOrNull { it.id == id } ?: Landscape
    }
}
