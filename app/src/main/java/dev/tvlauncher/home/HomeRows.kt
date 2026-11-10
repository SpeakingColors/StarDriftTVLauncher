package dev.tvlauncher.home

import android.content.SharedPreferences

/**
 * The three rows under the header (Apps, Picks, Continue watching): which ones show and in what order.
 * Both are kept in preferences. A row that is switched off takes no space, the others move up.
 */
object HomeRows {
    const val APPS = "apps"
    const val PICKS = "picks"
    const val CONTINUE = "continue"
    private val DEFAULT = listOf(APPS, PICKS, CONTINUE)

    /** Where the first row starts, in layout units. */
    private const val TOP = 230f

    /** Space each row takes including the gap below it, in layout units. */
    private fun height(id: String): Float = when (id) {
        APPS -> 204f
        PICKS -> 184f
        else -> 212f
    }

    fun name(id: String): String = when (id) {
        APPS -> "Apps"
        PICKS -> "Picks and New series"
        else -> "Continue watching"
    }

    /** All three rows in the chosen order, shown or not. */
    fun order(prefs: SharedPreferences): List<String> {
        val saved = (prefs.getString("rows_order", "") ?: "").split(',').filter { it in DEFAULT }.distinct()
        return saved + DEFAULT.filter { it !in saved }
    }

    fun isOn(prefs: SharedPreferences, id: String): Boolean = id !in off(prefs)

    fun visible(prefs: SharedPreferences): List<String> = order(prefs).filter { isOn(prefs, it) }

    fun setOn(prefs: SharedPreferences, id: String, on: Boolean) {
        val s = off(prefs).toMutableSet()
        if (on) s.remove(id) else s.add(id)
        prefs.edit().putString("rows_off", s.joinToString(",")).apply()
    }

    /** Moves a row one place up (delta -1) or down (delta 1) in the order. */
    fun move(prefs: SharedPreferences, id: String, delta: Int) {
        val l = order(prefs).toMutableList()
        val i = l.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= l.size) return
        l.removeAt(i)
        l.add(j, id)
        prefs.edit().putString("rows_order", l.joinToString(",")).apply()
    }

    /** Top of a row in layout units: the rows before it that are shown, stacked from [TOP]. */
    fun y(prefs: SharedPreferences, id: String): Float {
        var y = TOP
        for (r in visible(prefs)) {
            if (r == id) return y
            y += height(r)
        }
        return y
    }

    private fun off(prefs: SharedPreferences): Set<String> =
        (prefs.getString("rows_off", "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()
}
