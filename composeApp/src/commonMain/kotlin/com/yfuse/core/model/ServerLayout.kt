package com.yfuse.core.model

/**
 * How the 服务器 tab arranges its servers.
 *
 * The grid is the default because a saved server is compared with its neighbours — latency,
 * totals, how long since it was opened — and two columns put four of them on screen at once.
 * The list exists for the case the grid is bad at: a dozen servers whose names are long and
 * similar, where a full-width row can spell the name out and still have room for the address.
 */
enum class ServerLayout(
    val label: String,
) {
    Grid("网格"),
    List("列表"),
}
