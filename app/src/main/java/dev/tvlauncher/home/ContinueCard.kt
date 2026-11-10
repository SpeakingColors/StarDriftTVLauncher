package dev.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** Continue watching card: app name, title, time left and a progress bar. */
class ContinueCard(context: Context, private val appName: String, private val color: Int,
                   private val title: String, private val meta: String, private val progress: Float) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tag = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val mp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4BCCB.toInt() }
    private val r = RectF()
    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private val srcR = android.graphics.Rect()
    var image: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }

    /** Dark brand colors (Stremio purple) are hard to read as small text, so the tag is lightened. */
    private val tagColor: Int = run {
        val lum = (0.299 * android.graphics.Color.red(color) + 0.587 * android.graphics.Color.green(color) + 0.114 * android.graphics.Color.blue(color)) / 255.0
        if (lum < 0.26) Ui.mix(color, 0xFFFFFFFF.toInt(), 0.45f) else color
    }

    init {
        Ui.focusable(this, 1.06f)
        isLongClickable = true
        contentDescription = "$appName, $title"
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = Ui.px(18f)
        bg.color = Ui.CARD
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, rad, rad, bg)
        val img = image
        if (img != null) {
            clip.reset()
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val s = Math.max(w / img.width, h / img.height)
            val sw = w / s; val sh = h / s
            srcR.set(((img.width - sw) / 2f).toInt(), ((img.height - sh) / 2f).toInt(),
                ((img.width + sw) / 2f).toInt(), ((img.height + sh) / 2f).toInt())
            c.drawBitmap(img, srcR, r, imgPaint)
            shade.shader = android.graphics.LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0x66080616, 0xB3080616.toInt(), 0xF2080616.toInt()), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(r, shade)
            c.restore()
        }
        if (isFocused) {
            Ui.drawFocus(c, 0f, 0f, w, h, rad)
        } else {
            ring.color = Ui.STROKE
            ring.strokeWidth = Ui.px(1.2f)
            val hw = ring.strokeWidth / 2
            r.set(hw, hw, w - hw, h - hw)
            c.drawRoundRect(r, rad, rad, ring)
        }

        val padX = Ui.px(18f); val padY = Ui.px(14f)
        val tw = w - 2 * padX
        tag.textSize = Ui.px(15f); tag.color = tagColor
        c.drawText(TextUtils.ellipsize(appName, tag, tw, TextUtils.TruncateAt.END).toString(), padX, padY + Ui.px(14f), tag)

        // bottom block: bar, meta, title (grows upward)
        val barH = Ui.px(4f)
        val barY = h - padY - barH
        bar.color = 0x2EFFFFFF
        r.set(padX, barY, w - padX, barY + barH)
        c.drawRoundRect(r, barH / 2, barH / 2, bar)
        if (progress > 0f) {
            bar.color = color
            r.set(padX, barY, padX + tw * progress, barY + barH)
            c.drawRoundRect(r, barH / 2, barH / 2, bar)
        }
        mp.textSize = Ui.px(14f)
        val metaY = barY - Ui.px(8f)
        if (meta.isNotEmpty()) c.drawText(TextUtils.ellipsize(meta, mp, tw, TextUtils.TruncateAt.END).toString(), padX, metaY, mp)
        tp.textSize = Ui.px(19f)
        val lay = StaticLayout.Builder.obtain(title, 0, title.length, tp, tw.toInt())
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save()
        c.translate(padX, metaY - Ui.px(18f) - lay.height)
        lay.draw(c)
        c.restore()
    }
}
