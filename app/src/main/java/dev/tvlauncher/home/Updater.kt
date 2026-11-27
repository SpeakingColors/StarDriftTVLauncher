package dev.tvlauncher.home

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-launcher updates for your own copy of the project. The build of your fork knows its own repository
 * (BuildConfig.UPDATE_REPO, filled in by GitHub Actions) and looks at that repository's "latest" release:
 * the release text carries {"code":..,"name":".."} and the APK is the file tvlauncher.apk.
 * A build made on your own computer has no repository set, and then the Update button is simply not shown.
 * Nothing secret is involved: releases of a public repository can be read by anyone.
 */
object Updater {
    const val ACTION = "dev.tvlauncher.home.UPDATE_RESULT"

    class Info(val code: Int, val name: String, val apkUrl: String, val size: Long)

    fun enabled(): Boolean = BuildConfig.UPDATE_REPO.isNotEmpty()

    fun currentCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
    } catch (e: Throwable) { 0 }

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Stardrift-updater")
        return c
    }

    /** The "latest" release: by its tag, or from the release list when the tag lookup fails (it can for a while after a publish). */
    private fun readRelease(): JSONObject? {
        val base = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO
        val c = open("$base/releases/tags/latest", "application/vnd.github+json")
        val code = c.responseCode
        if (code == 200) {
            val j = JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            c.disconnect()
            return j
        }
        c.disconnect()
        android.util.Log.w("StardriftUpd", "release by tag: HTTP $code, trying the list")
        val l = open("$base/releases?per_page=10", "application/vnd.github+json")
        if (l.responseCode != 200) { android.util.Log.w("StardriftUpd", "release list: HTTP " + l.responseCode); l.disconnect(); return null }
        val arr = org.json.JSONArray(l.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        l.disconnect()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optString("tag_name") == "latest") return r
        }
        return null
    }

    /** Never on the main thread. Null when the release cannot be read. */
    fun fetchLatest(): Info? {
        if (!enabled()) return null
        return try {
            val j = readRelease() ?: return null
            val body = j.optString("body", "")
            val code = Regex("\"code\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toInt() ?: return null
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: code.toString()
            val assets = j.getJSONArray("assets")
            var found: Info? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == "tvlauncher.apk") found = Info(code, name, a.getString("browser_download_url"), a.getLong("size"))
            }
            found
        } catch (e: Throwable) { null }
    }

    /** Never on the main thread. [progress] gets 0..100. Throws with a message that is fine to show. */
    fun download(ctx: Context, info: Info, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "update"); dir.mkdirs()
        val out = File(dir, "tvlauncher.apk"); out.delete()
        val c = open(info.apkUrl, "application/octet-stream")   // GitHub sends it on to its file host; the connection follows
        if (c.responseCode != 200) { val code = c.responseCode; c.disconnect(); throw IllegalStateException("Download failed ($code)") }
        var done = 0L; var last = -1
        c.inputStream.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    val p = if (info.size > 0) (done * 100 / info.size).toInt() else 0
                    if (p != last) { last = p; progress(p) }
                }
            }
        }
        c.disconnect()
        if (info.size > 0 && out.length() != info.size) { out.delete(); throw IllegalStateException("Download incomplete") }
        return out
    }

    /** Hands the APK to Android's installer; the TV asks once for confirmation (see UpdateReceiver). */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { ins ->
                s.openWrite("tvlauncher.apk", 0, apk.length()).use { os -> ins.copyTo(os); s.fsync(os) }
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION)   // explicit: the receiver has no intent filter
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pending.intentSender)
        }
    }
}
