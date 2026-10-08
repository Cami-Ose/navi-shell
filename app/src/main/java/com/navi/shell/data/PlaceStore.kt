package com.navi.shell.data

import android.content.Context

/**
 * 地点簿：**常用**（它存下来的）+ **刚刚搜过**（自动记的）。
 *
 * 存法：SharedPreferences，换行分隔。一台手机的事，不上数据库。
 *
 * 为什么只存名字不存坐标：名字能重搜 —— 而且搜的时候会带上当前城市，
 * 比存下来的坐标更跟得上（它改名、搬家、同一家店开了分店都不会指错）。
 */
class PlaceStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("navi-shell_places", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 常用

    /** 常用的那几个（它自己存的）。 */
    fun favorites(): List<String> = read(KEY_FAV)

    /** 存成常用。已经在里面就挪到最前，不留重复。 */
    fun addFavorite(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        write(KEY_FAV, (listOf(n) + favorites().filter { it != n }).take(MAX_FAV))
    }

    fun removeFavorite(name: String) = write(KEY_FAV, favorites().filter { it != name.trim() })

    fun isFavorite(name: String): Boolean = favorites().any { it == name.trim() }

    // ---------------------------------------------------------------- 刚刚搜过

    fun recents(): List<String> = read(KEY_RECENT)

    /** 记一次搜索。已经在里面就挪到最前。 */
    fun addRecent(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        write(KEY_RECENT, (listOf(n) + recents().filter { it != n }).take(MAX_RECENT))
    }

    fun clearRecents() = write(KEY_RECENT, emptyList())

    // ---------------------------------------------------------------- 内部

    private fun read(key: String): List<String> =
        (prefs.getString(key, "") ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun write(key: String, list: List<String>) =
        prefs.edit().putString(key, list.joinToString("\n")).apply()

    private companion object {
        const val KEY_FAV = "favorites"
        const val KEY_RECENT = "recents"
        const val MAX_FAV = 16
        const val MAX_RECENT = 12
    }
}
