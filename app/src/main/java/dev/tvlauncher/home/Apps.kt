package dev.tvlauncher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

class AppEntry(val pkg: String, val label: String, val component: ComponentName, val icon: Drawable?, val custom: Bitmap? = null) {
    val tileColor: Int by lazy { dominant(icon) }
    val art: IconArt? by lazy {
        if (custom != null) IconArt(custom, android.graphics.Rect(0, 0, custom.width, custom.height), 0xFF000000.toInt(), 0f)
        else IconArt.build(icon, when (pkg) {
        "com.netflix.ninja" -> Recolor(0xFFE50914.toInt(), 0xFF000000.toInt())
        "com.spotify.tv.android" -> Recolor(0xFF1DB954.toInt(), 0xFF000000.toInt(), true, 0xFF1DB954.toInt())
        else -> null
    }, pkg == "com.android.vending", darkBg = label.lowercase() in DARK_BG_LABELS || pkg == "com.google.android.videos" || pkg == "com.google.android.play.games")
    }

    companion object {
        /** Apps whose icon is shown without its light background, on black. */
        val DARK_BG_LABELS = setOf("settings", "play movies & tv", "google play movies & tv", "play movies")

        fun dominant(d: Drawable?): Int {
            if (d == null) return 0xFF323A4E.toInt()
            return try {
                val b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                d.setBounds(0, 0, 12, 12)
                d.draw(c)
                var r = 0L; var g = 0L; var bl = 0L; var n = 0L
                for (y in 0 until 12) for (x in 0 until 12) {
                    val p = b.getPixel(x, y)
                    if (Color.alpha(p) > 128) { r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++ }
                }
                b.recycle()
                if (n == 0L) return 0xFF323A4E.toInt()
                val hsv = FloatArray(3)
                Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), hsv)
                hsv[1] = Math.min(1f, hsv[1] * 1.1f)
                hsv[2] = hsv[2].coerceIn(0.32f, 0.62f)
                Color.HSVToColor(hsv)
            } catch (e: Throwable) {
                0xFF323A4E.toInt()
            }
        }
    }
}

/** Installed apps plus the pinned order that is kept in preferences. */
class AppsRepo(private val context: Context, private val prefs: SharedPreferences) {

    // Package names are my best guesses for the apps I use; apps that are not installed are skipped.
    private val preferred = listOf(
        "com.stremio.one", "com.crunchyroll.crunchyroid", "com.netflix.ninja", "com.google.android.youtube.tv",
        "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom", "com.spotify.tv.android", "com.govee.home"
    )

    /** Apps shown in the launcher (hidden ones removed). */
    var all: List<AppEntry> = emptyList()
        private set

    /** Apps the user hid; they stay installed and can be shown again from Settings. */
    var hiddenApps: List<AppEntry> = emptyList()
        private set

    fun hiddenPackages(): Set<String> =
        (prefs.getString("hidden", "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()

    fun setHidden(pkg: String, hide: Boolean) {
        val s = hiddenPackages().toMutableSet()
        if (hide) s.add(pkg) else s.remove(pkg)
        prefs.edit().putString("hidden", s.joinToString(",")).apply()
        load()
    }

    fun load() {
        val pm = context.packageManager
        val map = LinkedHashMap<String, AppEntry>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == context.packageName) continue
                if (map.containsKey(ai.packageName)) continue
                val label = ri.loadLabel(pm).toString()
                val custom = when (label.lowercase()) {
                    "eco dashboard" -> R.drawable.icon_eco
                    "timers & clock" -> R.drawable.icon_timer
                    else -> 0
                }
                map[ai.packageName] = AppEntry(ai.packageName, label,
                    ComponentName(ai.packageName, ai.name), try { ri.loadIcon(pm) } catch (e: Throwable) { null },
                    if (custom != 0) android.graphics.BitmapFactory.decodeResource(context.resources, custom) else null)
            }
        }
        val sorted = map.values.sortedBy { it.label.lowercase() }
        val hid = hiddenPackages()
        all = sorted.filter { it.pkg !in hid }
        hiddenApps = sorted.filter { it.pkg in hid }
    }

    fun pinnedPackages(): MutableList<String> {
        val saved = prefs.getString("pinned", null)
        val installed = all.map { it.pkg }.toSet()
        val list: MutableList<String> = if (saved == null) {
            val d = preferred.filter { it in installed }.toMutableList()
            if (d.isEmpty()) all.take(8).map { it.pkg }.toMutableList() else d
        } else {
            saved.split(',').filter { it.isNotEmpty() && it in installed }.toMutableList()
        }
        return list
    }

    fun savePinned(list: List<String>) {
        prefs.edit().putString("pinned", list.joinToString(",")).apply()
    }

    fun pinned(): List<AppEntry> {
        val byPkg = all.associateBy { it.pkg }
        return pinnedPackages().mapNotNull { byPkg[it] }
    }
}
