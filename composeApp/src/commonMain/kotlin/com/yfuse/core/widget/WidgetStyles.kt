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
    ImageTop("image-top", "上图下文", "横图信息", "整幅剧照 · 完整信息面板"),
    TextTop("text-top", "上文下图", "横图信息", "信息标题在上 · 图片在下"),
    Poster("poster", "单排竖卡", "海报展示", "2:3 海报 · 横向排列"),
    DoublePoster("double-poster", "双排海报", "海报展示", "两排交错浏览 · 更多内容"),
    GridTwo("grid-two", "双列海报网格", "海报展示", "两列大海报 · 纵向展开"),
    GridThree("grid-three", "三列海报网格", "海报展示", "三列海报 · 紧凑陈列"),
    PosterDetail("poster-detail", "海报详情长卡", "海报展示", "竖版海报与完整简介并列"),
    PosterStack("poster-stack", "叠放片单卡", "海报展示", "倾斜叠放海报 · 片单封面"),
    Hero("hero", "沉浸式大图", "焦点推荐", "背景大图 · 渐变标题"),
    PeekCarousel("peek-carousel", "相邻露出轮播", "焦点推荐", "横向翻页 · 预览下一部"),
    FeatureThree("feature-three", "一大两小组合", "焦点推荐", "主推荐在上 · 双卡在下"),
    FeatureList("feature-list", "主推与列表", "焦点推荐", "左侧焦点海报 · 右侧内容列表"),
    Magazine("magazine", "杂志拼贴", "焦点推荐", "交替大小图 · 编辑精选"),
    CenterPoster("center-poster", "居中海报轮播", "焦点推荐", "聚焦中央海报 · 翻页浏览"),
    RankRail("rank-rail", "大数字横向榜单", "排行榜", "编号与海报并列 · 横向滚动"),
    RankList("rank-list", "紧凑排名列表", "排行榜", "名次 · 缩略图 · 影片信息"),
    Podium("podium", "前三名领奖台", "排行榜", "中央榜首 · 两侧第二第三名"),
    RankFeature("rank-feature", "榜首大卡与列表", "排行榜", "突出第一名 · 后续紧凑排列"),
    GroupRanks("group-ranks", "分栏分类榜", "排行榜", "按分类分组 · 各组独立编号"),
    RankGrid("rank-grid", "排名海报网格", "排行榜", "海报矩阵 · 左上角排名"),
    ContinueLandscape("continue-landscape", "横向进度卡", "续播追剧", "剧照与播放进度 · 继续观看"),
    ContinuePoster("continue-poster", "竖向进度卡", "续播追剧", "海报与播放进度 · 标注已看比例"),
    Episodes("episodes", "分集缩略图列表", "续播追剧", "剧照 · 季集编号 · 集简介"),
    NextEpisode("next-episode", "下一集焦点卡", "续播追剧", "突出下一集 · 展开后续剧集"),
    Calendar("calendar", "每周追剧日历", "续播追剧", "按播出日期分组 · 横向浏览"),
    Timeline("timeline", "更新动态时间线", "续播追剧", "日期节点 · 纵向更新记录"),
    Categories("categories", "方形分类卡", "分类合集", "方形封面 · 分类导航"),
    People("people", "圆形人物卡", "分类合集", "圆形头像 · 姓名与介绍"),
    Tags("tags", "标签导航", "分类合集", "自动换行标签 · 轻量入口"),
    Collection("collection", "多海报合集封面", "分类合集", "四张封面拼图 · 合集入口"),
    Expandable("expandable", "可展开分组列表", "分类合集", "收起分组 · 展开季集或内容"),
    TextList("text-list", "简洁文字列表", "分类合集", "标题 · 年份 · 评分 · 无图片"),
    ;

    companion object {
        fun fromId(id: String): WidgetStyle = entries.firstOrNull { it.id == id } ?: Landscape
    }
}
