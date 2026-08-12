package com.yfuse.core.model

/**
 * Where a cold start lands.
 *
 * [Automatic] is the behaviour the shell had before this was a setting, and it stays the
 * default because it is right for both ends of the first-run/长期使用 split: with nothing
 * connected yet 首页 is the only screen with anything on it, and once a server exists the
 * library is what the app was opened for. The explicit choices exist because that guess is
 * still a guess — someone who lives in 搜索, or who juggles several servers, knows better
 * than the rule does.
 */
enum class StartupTab(val label: String, val description: String) {
    Automatic("自动", "有服务器时进入「库」，否则进入「首页」"),
    Home("首页", "每次都从推荐开始"),
    Library("库", "直接进入媒体库"),
    Servers("服务器", "先挑一台服务器"),
}
