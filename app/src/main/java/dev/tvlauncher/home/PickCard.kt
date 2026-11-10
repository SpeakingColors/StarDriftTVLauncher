package dev.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** One of the three picks cards. It cycles through the newest items of its category. */
class PickCard(context: Context, val category: FeedCategory) : View(context) {
    private var items: List<FeedItem> = emptyList()
    private var idx = 0
    private var emptyText = "Loading..."
    var startOffset = 0
    private var started = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tag = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4BCCB.toInt() }
    private val r = RectF()
    private val clip = Path()

    init {
        Ui.focusable(this, 1.06f)
        isLongClickable = true
        contentDescription = category.tag
    }

    val current: FeedItem? get() = items.getOrNull(idx)

    fun setItems(list: List<FeedItem>, message: String) {
        items = list
        if (!started && list.isNotEmpty()) { idx = startOffset % list.size; started = true }
        if (idx >= items.size) idx = 0
        emptyText = message
        invalidate()
    }

    fun next(step: Int = 1) {
        if (items.size > 1) { idx = (idx + step + items.size) % items.size; invalidate() }
    }

    fun imageChanged() = invalidate()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = Ui.px(20f)
        bg.color = Ui.CARD
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, rad, rad, bg)
        if (isFocused) {
            Ui.drawFocus(c, 0f, 0f, w, h, rad)
        } else {
            ring.color = Ui.STROKE
            ring.strokeWidth = Ui.px(1.2f)
            val hw = ring.strokeWidth / 2
            r.set(hw, hw, w - hw, h - hw)
            c.drawRoundRect(r, rad, rad, ring)
        }

        val pad = Ui.px(12f)
        val imgW = Ui.px(150f)
        val it = items.getOrNull(idx)
        // image area, always filled: article image, else a colored placeholder
        r.set(pad, pad, pad + imgW, h - pad)
        val ir = Ui.px(12f)
        c.save()
        clip.reset(); clip.addRoundRect(r, ir, ir, Path.Direction.CW); c.clipPath(clip)
        val bmp = Images.peek(it?.imageUrl)
        if (bmp != null) {
            val sc = Math.max(r.width() / bmp.width, r.height() / bmp.height)
            val dw = bmp.width * sc; val dh = bmp.height * sc
            val l = r.left + (r.width() - dw) / 2; val t = r.top + (r.height() - dh) / 2
            c.drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), null)
        } else {
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                Ui.mix(it?.tagColor?.takeIf { v -> v != 0 } ?: category.color, 0xFF1A1440.toInt(), 0.6f), 0xFF1A1440.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(r, p)
            p.shader = null
        }
        c.restore()

        val tx = pad + imgW + Ui.px(16f)
        val tw = w - tx - Ui.px(18f)
        tag.textSize = Ui.px(16f)
        tag.color = it?.tagColor?.takeIf { v -> v != 0 } ?: category.color
        c.drawText(it?.tagText ?: category.tag, tx, pad + Ui.px(18f), tag)
        // dots
        val n = Math.min(items.size, 8)
        if (n > 1) {
            val d = Ui.px(7f); val g = Ui.px(5f)
            var x = w - Ui.px(18f) - n * d - (n - 1) * g
            val y = pad + Ui.px(12f)
            for (i in 0 until n) {
                p.color = if (i == idx % n) 0xFFFFFFFF.toInt() else 0x4DFFFFFF
                c.drawCircle(x + d / 2, y, d / 2, p)
                x += d + g
            }
        }
        if (it == null) {
            title.textSize = Ui.px(20f)
            c.drawText(TextUtils.ellipsize(emptyText, title, tw, TextUtils.TruncateAt.END).toString(), tx, h / 2f, title)
            return
        }
        title.textSize = Ui.px(20f)
        val lay = StaticLayout.Builder.obtain(it.title, 0, it.title.length, title, tw.toInt())
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.1f)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val top = pad + Ui.px(30f)
        val avail = h - pad - Ui.px(30f) - Ui.px(30f)
        c.save()
        c.translate(tx, top + Math.max(0f, (avail - lay.height) / 2f))
        lay.draw(c)
        c.restore()
        meta.textSize = Ui.px(15f)
        val m = (it.source + (if (it.time > 0) ", " + Feeds.ago(it.time) else ""))
        c.drawText(TextUtils.ellipsize(m, meta, tw, TextUtils.TruncateAt.END).toString(), tx, h - pad - Ui.px(4f), meta)
    }
}
