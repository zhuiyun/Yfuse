package com.yfuse.core.filesource

fun validateFileSourceMove(
    from: List<String>,
    to: List<String>,
) {
    require(from.isNotEmpty() && to.isNotEmpty() && from != to) { "请选择不同的目标路径" }
    require(
        (from + to).all {
            it.isNotBlank() &&
                it !in setOf(".", "..") &&
                it.none { c -> c == '/' || c == '\\' || c.isISOControl() }
        },
    ) { "文件名或路径无效" }
    require(to.take(from.size) != from) { "不能将文件夹移动到自身内部" }
}
