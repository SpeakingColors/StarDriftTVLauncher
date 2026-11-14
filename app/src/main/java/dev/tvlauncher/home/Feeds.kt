package dev.tvlauncher.home

import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class FeedCategory(val id: String, val tag: String, val color: Int)

class FeedSource(val id: String, val category: String, val name: String, val url: String, val builtin: Boolean, val defaultOn: Boolean = true)

class FeedItem(val title: String, val link: String, val time: Long, val source: String, var imageUrl: String?, val category: String) {
    var imageResolved = imageUrl != null
    /** Article text from the feed (plain text), empty when the feed only sends the title. */
    var body: String = ""
    /** Set for items that come from an app (New series card): the app name and its colour replace the category tag. */
    var tagText: String? = null
    var tagColor: Int = 0
}

object Feeds {
    // The only built-in card is "New series" (not a web feed: what your streaming apps share with the TV).
    // Web feeds are always the user's own, added in Edit sources.
    val categories = listOf(
        FeedCategory("crapp", "New series", 0xFFB69CFF.toInt())
    )

    val builtins = listOf(
        // not a web feed: what the apps chosen in NewSeries share with the TV home screen (card hidden when empty)
        FeedSource("crapp", "crapp", "New series from your apps", "tv:apps", true)
    )

    /** At most this many cards fit in the Picks row. */
    const val MAX_CARDS = 4

    private val palette = intArrayOf(0xFFFFC857.toInt(), 0xFF7AA2FF.toInt(), 0xFFFF8A6B.toInt(), 0xFF9BE15D.toInt(), 0xFFE59BFF.toInt(), 0xFF5ED3F3.toInt())

    // ---------- cards you make yourself ----------

    /** Cards made by the user, kept in preferences as lines of "id, name, colour number". */
    fun customCards(p: SharedPreferences): List<FeedCategory> {
        val raw = p.getString("custom_cards", "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val a = line.split('\t')
            if (a.size < 3) null else FeedCategory(a[0], a[1], palette[(a[2].toIntOrNull() ?: 0).mod(palette.size)])
        }
    }

    /** The built-in cards followed by the ones you made. */
    fun cards(p: SharedPreferences): List<FeedCategory> = categories + customCards(p)

    /** Cards a feed can be shown under (everything except the apps card). */
    fun feedCards(p: SharedPreferences): List<FeedCategory> = cards(p).filter { it.id != "crapp" }

    fun card(p: SharedPreferences, id: String): FeedCategory =
        cards(p).firstOrNull { it.id == id } ?: FeedCategory(id, "Feeds", palette[0])

    @Synchronized
    fun addCard(p: SharedPreferences, name: String): String {
        val clean = name.replace('\t', ' ').replace('\n', ' ').trim().take(24).ifEmpty { "My feeds" }
        val old = customCards(p)
        val next = (old.mapNotNull { it.id.removePrefix("k").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val raw = p.getString("custom_cards", "") ?: ""
        p.edit().putString("custom_cards", raw + "k" + next + "\t" + clean + "\t" + old.size + "\n").apply()
        return "k$next"
    }

    fun renameCard(p: SharedPreferences, id: String, name: String) {
        val clean = name.replace('\t', ' ').replace('\n', ' ').trim().take(24)
        if (clean.isEmpty()) return
        val raw = p.getString("custom_cards", "") ?: ""
        val out = raw.split('\n').filter { it.isNotBlank() }.map { line ->
            val a = line.split('\t')
            if (a.size >= 3 && a[0] == id) a[0] + "\t" + clean + "\t" + a[2] else line
        }
        p.edit().putString("custom_cards", out.joinToString("\n") + if (out.isEmpty()) "" else "\n").apply()
    }

    /** Removes a card and the feeds you added under it. Built-in feeds you moved into it go back to their own card. */
    fun removeCard(p: SharedPreferences, id: String) {
        for (s in allSources(p)) {
            if (cardOf(p, s) != id) continue
            if (s.builtin) p.edit().remove("card_" + s.id).apply() else removeCustom(p, s.url)
        }
        val raw = p.getString("custom_cards", "") ?: ""
        val out = raw.split('\n').filter { it.isNotBlank() && it.split('\t')[0] != id }
        p.edit().putString("custom_cards", out.joinToString("\n") + if (out.isEmpty()) "" else "\n").apply()
    }

    /** The card a source is shown under: the one you moved it to, else the one it was added under. */
    fun cardOf(p: SharedPreferences, s: FeedSource): String = p.getString("card_" + s.id, null) ?: s.category

    fun moveSource(p: SharedPreferences, s: FeedSource, cardId: String) {
        p.edit().putString("card_" + s.id, cardId).apply()
    }

    // ---------- source list kept in preferences ----------

    fun customSources(p: SharedPreferences): List<FeedSource> {
        val raw = p.getString("custom_sources", "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val a = line.split('\t')
            if (a.size < 3) null else FeedSource("c" + a[2].hashCode(), a[0], a[1], a[2], false)
        }
    }

    @Synchronized
    fun addCustom(p: SharedPreferences, category: String, name: String, url: String) {
        val old = p.getString("custom_sources", "") ?: ""
        if (old.contains("\t" + url + "\n")) return
        p.edit().putString("custom_sources", old + category + "\t" + name.replace('\t', ' ').replace('\n', ' ') + "\t" + url + "\n").apply()
    }

    fun removeCustom(p: SharedPreferences, url: String) {
        val old = p.getString("custom_sources", "") ?: ""
        val keep = old.split('\n').filter { it.isNotBlank() && it.split('\t').getOrNull(2) != url }
        p.edit().putString("custom_sources", keep.joinToString("\n") + if (keep.isEmpty()) "" else "\n").apply()
    }

    fun allSources(p: SharedPreferences): List<FeedSource> = builtins + customSources(p)

    fun isOn(p: SharedPreferences, s: FeedSource): Boolean = p.getBoolean("src_" + s.id, s.defaultOn)

    fun setOn(p: SharedPreferences, s: FeedSource, on: Boolean) {
        p.edit().putBoolean("src_" + s.id, on).apply()
    }

    // ---------- network ----------

    private const val UA = "Mozilla/5.0 (Linux; Android 12; Google TV) tvlauncher/0.3"

    fun httpGet(url: String, maxBytes: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 12_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP " + c.responseCode)
            val out = StringBuilder()
            c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(8192)
                while (out.length < maxBytes) {
                    val n = r.read(buf)
                    if (n < 0) break
                    out.append(buf, 0, n)
                }
            }
            return out.toString()
        } finally {
            c.disconnect()
        }
    }

    fun fetch(s: FeedSource): List<FeedItem> = parse(httpGet(s.url, 3_000_000), s)

    // ---------- parsing (RSS 2.0 and Atom) ----------

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd HH:mm:ss Z"
    )

    fun parseDate(raw: String?): Long {
        if (raw == null) return 0L
        var s = raw.trim()
        if (s.endsWith("Z")) s = s.dropLast(1) + "+0000"
        s = s.replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
        for (f in dateFormats) {
            try {
                val d = SimpleDateFormat(f, Locale.US).parse(s)
                if (d != null) return d.time
            } catch (e: Exception) { }
        }
        return 0L
    }

    private val imgTagRe = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

    private fun usable(u: String): Boolean {
        if (!u.startsWith("http")) return false
        val l = u.lowercase()
        return !(l.contains("logo") || l.contains("placeholder") || l.endsWith(".svg") || l.contains("avatar") ||
            l.contains("emoji") || l.contains("pixel") || l.contains("spacer"))
    }

    /** First usable picture in the item text: skips logos, svg placeholders, reads lazy data-src too. */
    private fun firstImg(html: String?): String? {
        if (html == null) return null
        for (tag in imgTagRe.findAll(html)) {
            for (attr in arrayOf("src", "data-src")) {
                val m = Regex("\\b" + attr + "=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag.value) ?: continue
                val v = m.groupValues[1].replace("&amp;", "&")
                if (usable(v)) return v
            }
        }
        return null
    }

    fun parse(xml: String, src: FeedSource): List<FeedItem> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(java.io.StringReader(xml))
        val items = ArrayList<FeedItem>()
        var inItem = false
        var title: String? = null; var link: String? = null; var date: String? = null
        var img: String? = null; var imgBig: String? = null; var html: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                if (n == "item" || n == "entry") {
                    inItem = true; title = null; link = null; date = null; img = null; imgBig = null; html = null
                } else if (inItem) {
                    when (n) {
                        "title" -> if (title == null) title = p.nextText().trim()
                        "link" -> {
                            val href = p.getAttributeValue(null, "href")
                            if (href != null) { if (link == null || p.getAttributeValue(null, "rel") == "alternate") link = href }
                            else if (link == null) link = p.nextText().trim()
                        }
                        "pubDate", "published", "updated", "dc:date" -> if (date == null) date = p.nextText()
                        "media:thumbnail" -> if (img == null) img = p.getAttributeValue(null, "url")
                        "media:content" -> {
                            val type = p.getAttributeValue(null, "type") ?: ""
                            val medium = p.getAttributeValue(null, "medium") ?: ""
                            if (imgBig == null && (medium == "image" || type.startsWith("image"))) imgBig = p.getAttributeValue(null, "url")
                        }
                        "enclosure" -> {
                            val type = p.getAttributeValue(null, "type") ?: ""
                            if (img == null && type.startsWith("image")) img = p.getAttributeValue(null, "url")
                        }
                        "description", "content:encoded", "content", "summary" -> {
                            val t = p.nextText()
                            if (html == null || n == "content:encoded") html = t
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && (p.name == "item" || p.name == "entry") && inItem) {
                inItem = false
                val t = title
                val l = link
                if (!t.isNullOrBlank() && !l.isNullOrBlank()) {
                    items.add(FeedItem(t, l, parseDate(date), src.name, imgBig ?: img ?: firstImg(html), src.category).also { it.body = htmlToText(html) })
                }
            }
            ev = p.next()
        }
        return items
    }

    /** Item text from the feed as plain paragraphs. */
    fun htmlToText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        val s = try {
            android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } catch (e: Throwable) {
            html.replace(Regex("<[^>]*>"), " ")
        }
        return s.replace('\uFFFC', ' ').replace('\u00a0', ' ')
            .lines().map { it.trim() }.filterNot { it.contains("appeared first on", true) }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim().take(20000)
    }

    /** Preview image of the article itself, used when the feed has none. */
    fun ogImage(pageUrl: String): String? {
        return try {
            val html = httpGet(pageUrl, 200_000)
            val a = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            val b = Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']", RegexOption.IGNORE_CASE).find(html)
            (a ?: b)?.groupValues?.get(1)?.replace("&amp;", "&")?.takeIf { it.startsWith("http") }
        } catch (e: Throwable) {
            null
        }
    }

    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0L) return ""
        val m = (now - time) / 60000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 60 * 24 -> "${m / 60} h ago"
            m < 60 * 48 -> "yesterday"
            else -> "${m / 60 / 24} days ago"
        }
    }
}

/** Holds the newest items per category. Refreshed in the background. */
class FeedStore(private val prefs: SharedPreferences, private val ctx: android.content.Context) {
    @Volatile var byCategory: Map<String, List<FeedItem>> = emptyMap()
        private set
    @Volatile var errors: Map<String, String> = emptyMap()
        private set
    @Volatile var loaded = false
        private set

    /** Blocking. Call from a worker thread. */
    fun refresh() {
        val out = HashMap<String, MutableList<FeedItem>>()
        val err = HashMap<String, String>()
        for (s in Feeds.allSources(prefs)) {
            if (!Feeds.isOn(prefs, s)) continue
            val card = Feeds.cardOf(prefs, s)
            try {
                if (s.url.startsWith("app:")) {
                    // one streaming app's rows from the TV home screen, shown under the card it was added to
                    val pkg = s.url.removePrefix("app:")
                    val items = WatchNext.previewItems(ctx, pkg, s.name) ?: emptyList()
                    if (items.isNotEmpty()) out.getOrPut(card) { ArrayList() }.addAll(items)
                    continue
                }
                if (s.url.startsWith("tv:")) {
                    val lists = NewSeries.chosen(prefs).mapNotNull { pkg ->
                        WatchNext.previewItems(ctx, pkg, CwApps.known[pkg] ?: pkg)?.takeIf { it.isNotEmpty() }
                    }
                    // one item from each chosen app in turn, so every app shows up
                    val merged = ArrayList<FeedItem>()
                    var i = 0
                    while (merged.size < 20 && lists.any { i < it.size }) {
                        for (l in lists) if (i < l.size) merged.add(l[i])
                        i++
                    }
                    if (merged.isNotEmpty()) out.getOrPut(card) { ArrayList() }.addAll(merged)
                    continue
                }
                out.getOrPut(card) { ArrayList() }.addAll(Feeds.fetch(s).take(12))
            } catch (e: Throwable) {
                err[s.id] = e.message ?: e.javaClass.simpleName
            }
        }
        val sorted = HashMap<String, List<FeedItem>>()
        for ((k, v) in out) {
            // newest first, but keep a mix of sources: take items round robin by source after sorting
            sorted[k] = if (k == "crapp") v.take(20) else if (v.all { it.time == 0L }) v.take(20) else v.sortedByDescending { it.time }.take(20)
        }
        byCategory = sorted
        errors = err
        loaded = true
    }
}
