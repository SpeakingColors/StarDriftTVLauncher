package dev.tvlauncher.home

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Full article text and pictures for the reader, taken from the article's own web page.
 * A light "reader mode": drop menus, scripts and share bars, find the block that holds most of the paragraphs,
 * keep its headings, paragraphs, list items and pictures in order. Returns null when the page gives too little
 * (login walls, script-only sites); the reader then keeps the feed's summary.
 */
object Article {
    const val HEADING = 0
    const val PARA = 1
    const val IMAGE = 2
    const val ITEM = 3

    class Block(val kind: Int, val text: String)

    /** Never on the main thread. */
    fun fetch(url: String): List<Block>? {
        return try {
            val html = Feeds.httpGet(url, 4_000_000)
            val body = Jsoup.parse(html, url).body() ?: return null
            extract(body)
        } catch (e: Throwable) {
            android.util.Log.w("StardriftReader", "article failed: $url", e)
            null
        }
    }

    private fun extract(body: Element): List<Block>? {
        body.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg, button, select, " +
            "[role=navigation], [role=banner], [role=complementary], [aria-hidden=true], " +
            "[class*=share], [class*=social], [class*=newsletter], [class*=related], [class*=comment], " +
            "[class*=advert], [class*=promo], [class*=subscribe], [id*=comment], [id*=related]").remove()
        val root = body.selectFirst("article") ?: best(body) ?: return null
        val out = ArrayList<Block>()
        var chars = 0
        var images = 0
        val seen = HashSet<String>()
        for (el in root.select("h2, h3, p, li, img")) {
            when (el.tagName()) {
                "h2", "h3" -> {
                    val t = el.text().trim()
                    if (t.length in 3..160) out.add(Block(HEADING, t))
                }
                "p" -> {
                    val t = el.text().trim()
                    if (t.length >= 40 || (t.length >= 15 && t.last() in ".!?:")) {
                        if (seen.add(t)) { out.add(Block(PARA, t)); chars += t.length }
                    }
                }
                "li" -> {
                    if (el.selectFirst("p") != null) continue   // its paragraphs come on their own
                    val t = el.text().trim()
                    if (t.length in 20..400 && seen.add(t)) { out.add(Block(ITEM, t)); chars += t.length }
                }
                "img" -> {
                    if (images >= 8) continue
                    val src = imageUrl(el) ?: continue
                    val w = el.attr("width").toIntOrNull()
                    if (w != null && w < 160) continue
                    if (seen.add(src)) { out.add(Block(IMAGE, src)); images++ }
                }
            }
        }
        // drop headings at the end that announce nothing (e.g. "Related", "More from")
        while (out.isNotEmpty() && out.last().kind != PARA && out.last().kind != ITEM) out.removeAt(out.size - 1)
        return if (chars < 400) null else out
    }

    /** The element whose own paragraphs hold the most text. */
    private fun best(body: Element): Element? {
        var top: Element? = null
        var topScore = 0
        for (el in body.select("main, section, div")) {
            var score = 0
            for (c in el.children()) if (c.tagName() == "p") score += c.text().length
            if (score > topScore) { topScore = score; top = el }
        }
        return top
    }

    private fun imageUrl(el: Element): String? {
        // lazy pages put a placeholder (data:) in src and the real picture in data-src or srcset (last = largest)
        val set = el.attr("srcset").ifEmpty { el.attr("data-srcset") }
        val fromSet = set.split(",").map { it.trim().split(" ").first() }.lastOrNull { it.isNotEmpty() }?.let { resolve(el, it) } ?: ""
        val cands = listOf(el.absUrl("src"), el.absUrl("data-src"), el.absUrl("data-lazy-src"), fromSet)
        val u = cands.firstOrNull { it.startsWith("http") } ?: return null
        val low = u.lowercase()
        if (low.contains(".svg") || low.contains("pixel") || low.contains("tracking") || low.contains("data:")) return null
        return u
    }

    private fun resolve(el: Element, rel: String): String = try {
        java.net.URL(java.net.URL(el.baseUri()), rel).toString()
    } catch (e: Throwable) { "" }
}
