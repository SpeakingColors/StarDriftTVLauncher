package dev.tvlauncher.home

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Download speed and latency against Cloudflare's public speed test endpoint
 * (https://speed.cloudflare.com/__down?bytes=N). Runs only when the user presses the button.
 */
object SpeedTest {
    const val HOST = "speed.cloudflare.com"
    private const val BASE = "https://speed.cloudflare.com/__down?bytes="
    private const val THREADS = 4
    private const val WARMUP_MS = 2500L

    /** Median time of a few tiny requests in ms, or null when the host cannot be reached. */
    fun latencyMs(): Int? {
        val times = ArrayList<Long>()
        for (i in 0 until 6) {
            val t0 = System.nanoTime()
            try {
                val c = URL(BASE + "0").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000; c.useCaches = false
                c.inputStream.use { it.readBytes() }
                c.disconnect()
            } catch (e: Throwable) {
                return null
            }
            if (i > 0) times.add((System.nanoTime() - t0) / 1_000_000L)  // first one includes the TLS setup
        }
        times.sort()
        return times[times.size / 2].toInt()
    }

    /**
     * Downloads for [seconds] on several connections. [onProgress] gets (Mbit/s so far, seconds left).
     * Returns Mbit/s measured after a short warm up, or null when nothing could be downloaded.
     */
    fun download(seconds: Int, cancel: AtomicBoolean, onProgress: (Float, Int) -> Unit): Float? {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val end = start + seconds * 1000L
        val workers = ArrayList<Thread>()
        for (n in 0 until THREADS) {
            val th = Thread {
                val buf = ByteArray(64 * 1024)
                while (!cancel.get() && System.currentTimeMillis() < end) {
                    try {
                        val c = URL(BASE + "25000000").openConnection() as HttpURLConnection
                        c.connectTimeout = 6000; c.readTimeout = 6000; c.useCaches = false
                        c.inputStream.use { s ->
                            while (!cancel.get() && System.currentTimeMillis() < end) {
                                val r = s.read(buf)
                                if (r < 0) break
                                total.addAndGet(r.toLong())
                            }
                        }
                        c.disconnect()
                    } catch (e: Throwable) {
                        return@Thread
                    }
                }
            }
            th.start()
            workers.add(th)
        }
        var warmBytes = -1L
        var warmAt = 0L
        while (System.currentTimeMillis() < end && !cancel.get()) {
            Thread.sleep(500)
            val now = System.currentTimeMillis()
            if (warmBytes < 0 && now - start >= WARMUP_MS) { warmBytes = total.get(); warmAt = now }
            val mbps = if (warmBytes >= 0 && now - warmAt > 300) (total.get() - warmBytes) * 8f / ((now - warmAt) / 1000f) / 1_000_000f
                       else total.get() * 8f / (Math.max(1L, now - start) / 1000f) / 1_000_000f
            onProgress(mbps, Math.max(0, ((end - now) / 1000L).toInt()))
        }
        val stop = System.currentTimeMillis()
        for (w in workers) { try { w.join(1500) } catch (e: Throwable) { } }
        if (cancel.get() || total.get() == 0L || warmBytes < 0 || stop - warmAt < 1000) return null
        return (total.get() - warmBytes) * 8f / ((stop - warmAt) / 1000f) / 1_000_000f
    }
}
