package com.spotifydashboard.app.data

/**
 * How a board renders its items.
 *
 * `Grid` and `Compact` are both grids (compact is the dense one without the
 * metadata, like the web frontends), `List` is one row per item. The mode is
 * stored per board, so the library and the playlists can differ.
 */
enum class ViewMode(val key: String) {
    Grid("grid"),
    List("list"),
    Compact("compact");

    companion object {
        fun fromKey(key: String?): ViewMode =
            values().firstOrNull { it.key == key } ?: Grid
    }
}
