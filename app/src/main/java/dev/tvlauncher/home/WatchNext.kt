package dev.tvlauncher.home

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor

class CwItem(
    val pkg: String, val title: String, val episode: String, val intentUri: String?,
    val positionMs: Long, val durationMs: Long, val engagedMs: Long,
    val imageUri: String? = null
) {
    fun progress(): Float = if (durationMs > 0 && positionMs >= 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun left(): String {
        if (durationMs <= 0 || positionMs < 0) return episode
        val min = Math.max(1L, (durationMs - positionMs) / 60000L)
        return if (min >= 60) "${min / 60} h ${String.format("%02d", min % 60)} left" else "$min min left"
    }
}

/**
 * Google TV "Continue watching" rows. A normal launcher holding READ_TV_LISTINGS only gets rows
 * that apps mark searchable, so this can come back empty. Filtering is done in code because a
 * selection needs a permission that only system apps have.
 */
object WatchNext {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    private fun s(c: Cursor, col: String): String? {
        val i = c.getColumnIndex(col)
        return if (i < 0 || c.isNull(i)) null else c.getString(i)
    }

    private fun l(c: Cursor, col: String): Long {
        val i = c.getColumnIndex(col)
        return if (i < 0 || c.isNull(i)) -1L else c.getLong(i)
    }

    /** null when the permission is missing or the query fails. */
    fun read(ctx: Context): List<CwItem>? {
        if (ctx.checkSelfPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return try {
            val out = ArrayList<CwItem>()
            ctx.contentResolver.query(android.net.Uri.parse("content://android.media.tv/watch_next_program"), null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val pkg = s(c, "package_name") ?: continue
                    if (pkg == ctx.packageName) continue
                    val title = s(c, "title") ?: continue
                    out.add(CwItem(pkg, title, s(c, "episode_title") ?: "",
                        s(c, "intent_uri"),
                        l(c, "last_playback_position_millis"),
                        l(c, "duration_millis"),
                        l(c, "last_engagement_time_utc_millis"),
                        listOf(s(c, "thumbnail_uri"), s(c, "poster_art_uri")).firstOrNull { !it.isNullOrEmpty() }))
                }
            }
            out.sortedByDescending { it.engagedMs }
        } catch (e: Throwable) {
            null
        }
    }
}

/** Rows an app publishes to the TV home screen (preview channels). Same searchable limit as Watch Next. */
fun WatchNext.previewItems(ctx: Context, pkg: String, label: String): List<FeedItem>? {
    if (ctx.checkSelfPermission(WatchNext.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
    return try {
        val out = ArrayList<FeedItem>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(android.net.Uri.parse("content://android.media.tv/preview_program"), null, null, null, null)?.use { c ->
            fun col(n: String): String? { val i = c.getColumnIndex(n); return if (i < 0 || c.isNull(i)) null else c.getString(i) }
            while (c.moveToNext()) {
                if (col("package_name") != pkg) continue
                val title = col("title") ?: continue
                if (!seen.add(title)) continue
                val img = listOf(col("thumbnail_uri"), col("poster_art_uri")).firstOrNull { it != null && it.startsWith("http") }
                out.add(FeedItem(title, col("intent_uri") ?: "", 0L, "New on $label", img, "crapp").also {
                    it.tagText = label
                    it.tagColor = CwApps.color(pkg)
                })
            }
        }
        out.take(12)
    } catch (e: Throwable) {
        null
    }
}

/** Apps opened from this launcher, newest first. Used when Google TV gives no continue-watching rows. */
object Recents {
    fun add(p: SharedPreferences, pkg: String) {
        val now = System.currentTimeMillis()
        val list = get(p).filter { it.first != pkg }.toMutableList()
        list.add(0, pkg to now)
        p.edit().putString("recent", list.take(12).joinToString(",") { it.first + ":" + it.second }).apply()
    }

    fun get(p: SharedPreferences): List<Pair<String, Long>> =
        (p.getString("recent", "") ?: "").split(',').mapNotNull {
            val a = it.split(':')
            if (a.size == 2) a[1].toLongOrNull()?.let { t -> a[0] to t } else null
        }
}

/** Apps whose "new" rows can fill the New series card. Crunchyroll is on to start with, the rest are off. */
object NewSeries {
    val apps = listOf("com.crunchyroll.crunchyroid", "com.netflix.ninja", "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom")

    fun isOn(p: SharedPreferences, pkg: String): Boolean = p.getBoolean("ns_$pkg", pkg == "com.crunchyroll.crunchyroid")
    fun setOn(p: SharedPreferences, pkg: String, on: Boolean) { p.edit().putBoolean("ns_$pkg", on).apply() }
    fun chosen(p: SharedPreferences): List<String> = apps.filter { isOn(p, it) }
}

object CwApps {
    val known = mapOf(
        "com.crunchyroll.crunchyroid" to "Crunchyroll",
        "com.netflix.ninja" to "Netflix",
        "com.disney.disneyplus" to "Disney+",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.stremio.one" to "Stremio"
    )
    // Never shown in Continue watching: not useful to me.
    private val blocked = setOf("com.google.android.youtube.tv", "com.google.android.youtube.tvmusic",
        "com.google.android.youtube", "com.spotify.tv.android", "com.spotify.music")
    // On by default; every other app starts off and can be switched on in Choose apps.
    private val onByDefault = setOf("com.crunchyroll.crunchyroid", "com.netflix.ninja", "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom")

    fun isBlocked(pkg: String): Boolean = pkg in blocked

    fun isOn(p: SharedPreferences, pkg: String): Boolean = !isBlocked(pkg) && p.getBoolean("cw_$pkg", pkg in onByDefault)
    fun setOn(p: SharedPreferences, pkg: String, on: Boolean) { p.edit().putBoolean("cw_$pkg", on).apply() }

    fun color(pkg: String): Int = when (pkg) {
        "com.crunchyroll.crunchyroid" -> 0xFFF47521.toInt()
        "com.netflix.ninja" -> 0xFFE50914.toInt()
        "com.disney.disneyplus" -> 0xFF1AA6BC.toInt()
        "com.amazon.amazonvideo.livingroom" -> 0xFF00A0DC.toInt()
        "com.stremio.one" -> 0xFF6A0DAD.toInt()
        else -> intArrayOf(0xFF5EEAD4.toInt(), 0xFFB69CFF.toInt(), 0xFFFFD84D.toInt(), 0xFFFF9AC8.toInt())[Math.abs(pkg.hashCode()) % 4]
    }
}
