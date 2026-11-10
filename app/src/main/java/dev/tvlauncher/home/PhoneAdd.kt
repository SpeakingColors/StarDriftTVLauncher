package dev.tvlauncher.home

import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Tiny web page served on the home network while the "Add feeds from your phone" screen is open.
 * The phone opens the address shown on the TV, types the PIN shown on the TV, pastes feed addresses.
 * It stops as soon as the screen closes.
 */
class PhoneAdd(private val prefs: SharedPreferences, private val onAdded: (Int) -> Unit) {

    val pin: String = (1000 + SecureRandom().nextInt(9000)).toString()
    var port = 0
        private set
    var addedCount = 0
        private set

    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var wrongPins = 0

    fun start(): Boolean {
        for (p in 8765..8775) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (e: Throwable) {
            }
        }
        val s = server ?: return false
        running = true
        Thread {
            while (running) {
                try {
                    val sock = s.accept()
                    Thread { handle(sock) }.start()
                } catch (e: Throwable) {
                    if (!running) break
                }
            }
        }.start()
        return true
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Throwable) { }
        server = null
    }

    /** First private IPv4 address of this TV, or null. */
    fun address(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                }
            }
        } catch (e: Throwable) {
        }
        return null
    }

    // ---------- http ----------

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 4096) return null
        }
        return b.toString("UTF-8")
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 6000
            val input = sock.getInputStream()
            val first = readLine(input) ?: return
            val parts = first.split(" ")
            if (parts.size < 2) return
            var len = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) len = h.substring(15).trim().toIntOrNull() ?: 0
            }
            val method = parts[0]
            val path = parts[1].substringBefore("?")
            if (method == "GET" && path == "/") {
                send(sock, 200, page(null))
            } else if (method == "POST" && path == "/add" && len in 0..16000) {
                val body = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = input.read(body, got, len - got)
                    if (n < 0) break
                    got += n
                }
                send(sock, 200, page(add(String(body, 0, got, Charsets.UTF_8))))
            } else {
                send(sock, 404, "<p>Not found</p>")
            }
        } catch (e: Throwable) {
        } finally {
            try { sock.close() } catch (e: Throwable) { }
        }
    }

    private fun send(sock: Socket, code: Int, html: String) {
        val data = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + code + (if (code == 200) " OK" else " Not Found") +
            "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + data.size +
            "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val o = sock.getOutputStream()
        o.write(head.toByteArray(Charsets.UTF_8))
        o.write(data)
        o.flush()
    }

    // ---------- adding ----------

    private fun add(body: String): List<String> {
        val f = HashMap<String, String>()
        for (kv in body.split("&")) {
            val i = kv.indexOf('=')
            if (i < 0) continue
            try {
                f[URLDecoder.decode(kv.substring(0, i), "UTF-8")] = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            } catch (e: Throwable) {
            }
        }
        if (wrongPins >= 5) return listOf("Too many wrong PINs. Close this screen on the TV and open it again.")
        if (f["pin"]?.trim() != pin) { wrongPins++; return listOf("Wrong PIN. Look at the number shown on the TV.") }
        val cat = if (f["cat"] == "games") "games" else "anime"
        val out = ArrayList<String>()
        var n = 0
        for (raw in (f["urls"] ?: "").lines()) {
            val url = raw.trim()
            if (url.isEmpty()) continue
            if (n >= 20) { out.add("Only 20 addresses at a time."); break }
            n++
            if (!(url.startsWith("http://") || url.startsWith("https://"))) { out.add("Skipped (must start with http): " + url); continue }
            val name = try { android.net.Uri.parse(url).host ?: url } catch (e: Throwable) { url }
            val ok = try { Feeds.fetch(FeedSource("tmp", cat, name, url, false)).isNotEmpty() } catch (e: Throwable) { false }
            if (ok) {
                Feeds.addCustom(prefs, cat, name, url)
                addedCount++
                out.add("Added: " + url)
            } else out.add("Not a working feed: " + url)
        }
        if (n == 0) out.add("Nothing to add.")
        onAdded(addedCount)
        return out
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(results: List<String>?): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>TV feeds</title><style>")
        sb.append("body{margin:0;padding:20px;background:#07061a;color:#eef0f7;font:18px system-ui,sans-serif}")
        sb.append("h1{font-size:22px;margin:0 0 6px}p{color:#b4bccb;margin:6px 0 16px}")
        sb.append("textarea,input,select{width:100%;box-sizing:border-box;margin:6px 0 16px;padding:12px;border-radius:12px;border:1px solid #444;background:#12102a;color:#eef0f7;font:inherit}")
        sb.append("button{width:100%;padding:14px;border-radius:12px;border:0;background:#b69cff;color:#0d1117;font:inherit;font-weight:600}")
        sb.append("li{margin:6px 0;word-break:break-all}</style></head><body>")
        sb.append("<h1>Add feeds to the TV</h1>")
        if (results != null) {
            sb.append("<ul>")
            for (r in results) sb.append("<li>").append(esc(r)).append("</li>")
            sb.append("</ul><p><a style=\"color:#b69cff\" href=\"/\">Add more</a></p>")
        } else {
            sb.append("<p>One feed address per line. The PIN is shown on the TV.</p>")
            sb.append("<form method=\"post\" action=\"/add\">")
            sb.append("<label>Feed addresses</label><textarea name=\"urls\" rows=\"5\" placeholder=\"https://...\"></textarea>")
            sb.append("<label>Show them under</label><select name=\"cat\"><option value=\"anime\">Anime</option><option value=\"games\">Games</option></select>")
            sb.append("<label>PIN</label><input name=\"pin\" inputmode=\"numeric\" autocomplete=\"off\">")
            sb.append("<button type=\"submit\">Add</button></form>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }
}
