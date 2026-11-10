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

    /** Tile look chosen from the long press menu: "original", "black" (logo on black) or "white" (logo in the app's colour on a light tile). */
    var look: String = "original"
        set(v) { if (field != v) { field = v; artCache = null; artBuilt = false } }
    private var artCache: IconArt? = null
    private var artBuilt = false
    val art: IconArt?
        get() {
            if (!artBuilt) { artCache = buildArt(); artBuilt = true }
            return artCache
        }

    private fun buildArt(): IconArt? {
        if (custom != null) return IconArt(custom, android.graphics.Rect(0, 0, custom.width, custom.height), 0xFF000000.toInt(), 0f)
        val isPlay = pkg == "com.android.vending"
        if (!isPlay && look == "black") IconArt.build(icon, null, false, darkBg = true)?.let { return it }
        if (!isPlay && look == "white") {
            val brand = brandColor(pkg, icon)
            IconArt.build(icon, Recolor(brand, brand, false, LIGHT_TILE), false, darkBg = true, rejectFull = true)?.let { return it }
        }
        return IconArt.build(icon, when (pkg) {
            "com.netflix.ninja" -> Recolor(0xFFE50914.toInt(), 0xFF000000.toInt())
            "com.spotify.tv.android" -> Recolor(0xFF1DB954.toInt(), 0xFF000000.toInt(), true, 0xFF1DB954.toInt())
            else -> null
        }, isPlay, darkBg = label.lowercase() in DARK_BG_LABELS || pkg == "com.google.android.videos" || pkg == "com.google.android.play.games")
    }

    companion object {
        /** Apps whose icon is shown without its light background, on black. */
        val DARK_BG_LABELS = setOf("settings", "play movies & tv", "google play movies & tv", "play movies")

        private const val LIGHT_TILE = 0xFFF1F3F4.toInt()
        private const val DARK_LOGO = 0xFF1A1C24.toInt()

        /** Logo colours for the "white" look. Other apps take the colour of their own icon. */
        private val BRAND = mapOf(
            "com.netflix.ninja" to 0xFFE50914.toInt(),
            "com.google.android.youtube.tv" to 0xFFFF0000.toInt(),
            "com.spotify.tv.android" to 0xFF1DB954.toInt(),
            "com.crunchyroll.crunchyroid" to 0xFFF47521.toInt(),
            "com.disney.disneyplus" to 0xFF113CCF.toInt(),
            "com.amazon.amazonvideo.livingroom" to 0xFF00A8E1.toInt(),
            "com.valvesoftware.steamlink" to 0xFF1B2838.toInt()
        )

        /** Average colour of the visible pixels of a drawable, or null when it is nearly empty. */
        private fun avgOpaque(d: Drawable?): Int? {
            if (d == null) return null
            return try {
                val n = 16
                val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, n, n)
                d.draw(Canvas(b))
                var r = 0L; var g = 0L; var bl = 0L; var cnt = 0L
                for (y in 0 until n) for (x in 0 until n) {
                    val p = b.getPixel(x, y)
                    if (Color.alpha(p) > 200) { r += Color.red(p); g += Color.green(p); bl += Color.blue(p); cnt++ }
                }
                b.recycle()
                if (cnt < n * n / 10) null else Color.rgb((r / cnt).toInt(), (g / cnt).toInt(), (bl / cnt).toInt())
            } catch (e: Throwable) { null }
        }

        private fun luma(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

        /** The colour a logo gets on the light tile: a known brand colour, else the app icon's own background colour, else dark. */
        fun brandColor(pkg: String, d: Drawable?): Int {
            BRAND[pkg]?.let { return it }
            val layers = if (d is android.graphics.drawable.AdaptiveIconDrawable) listOf(d.background, d.foreground) else listOf(d)
            for (l in layers) {
                val c = avgOpaque(l)
                if (c != null && luma(c) < 0.75) return c
            }
            return DARK_LOGO
        }

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
                    if (custom != 0) android.graphics.BitmapFactory.decodeResource(context.resources, custom) else null
                ).also { it.look = prefs.getString("look_" + ai.packageName, "original") ?: "original" }
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
