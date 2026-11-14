package dev.tvlauncher.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small image loader with a memory cache. Callbacks run on the main thread. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    // failed downloads are retried after 8 seconds: right after boot the network may not be up yet
    private val failed = HashMap<String, Long>()
    private const val RETRY_MS = 8_000L

    private var appCtx: android.content.Context? = null
    fun init(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    private fun readBytes(url: String): ByteArray {
        if (url.startsWith("content://") || url.startsWith("android.resource://") || url.startsWith("file://")) {
            val c = appCtx ?: throw IllegalStateException("no context")
            return c.contentResolver.openInputStream(android.net.Uri.parse(url))!!.use { it.readBytes() }
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Google TV) tvlauncher/0.3")
        val bytes = c.inputStream.use { it.readBytes() }
        c.disconnect()
        return bytes
    }

    fun clearFailed() { synchronized(failed) { failed.clear() } }

    fun peek(url: String?): Bitmap? = if (url == null) null else cache.get(url)

    fun load(url: String, maxW: Int, done: (Bitmap?) -> Unit) {
        val hit = cache.get(url)
        if (hit != null) { done(hit); return }
        if (synchronized(failed) { val t = failed[url]; t != null && System.currentTimeMillis() - t < RETRY_MS }) { done(null); return }
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val bytes = readBytes(url)
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= maxW) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            } catch (e: Throwable) {
                bmp = null
            }
            if (bmp != null) { cache.put(url, bmp); synchronized(failed) { failed.remove(url) } } else synchronized(failed) { failed[url] = System.currentTimeMillis() }
            main.post { done(bmp) }
        }
    }
}
