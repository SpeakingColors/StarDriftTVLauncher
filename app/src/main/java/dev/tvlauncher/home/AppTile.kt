package dev.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** One app tile with its label underneath. [app] null draws the "All apps" tile. */
class AppTile(context: Context, val app: AppEntry?, private val tileU: Float, private val showLabel: Boolean = true) : View(context) {
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val clip = Path()
    private val dst = RectF()
    private val play = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var moving = false
        set(v) { field = v; invalidate() }

    init {
        Ui.focusable(this, 1.06f)
        isLongClickable = true
        contentDescription = app?.label ?: "All apps"
    }

    fun heightU(): Float = if (showLabel) tileU + 10f + 26f else tileU

    override fun onDraw(c: Canvas) {
        val t = Ui.px(tileU)
        val r = Ui.px(30f * tileU / 140f)
        rect.set(0f, 0f, width.toFloat(), t)
        val isPlay = app?.pkg == "com.android.vending"
        tilePaint.color = if (app == null) 0xFF000000.toInt()
            else if (isPlay && IconStyle.play == "black") 0xFF000000.toInt()
            else (app.art?.bg ?: app.tileColor)
        c.drawRoundRect(rect, r, r, tilePaint)
        if (app == null) {
            if (!isFocused) {
                ring.color = 0x2EFFFFFF
                ring.strokeWidth = Ui.px(1.2f)
                val h0 = ring.strokeWidth / 2f
                rect.set(h0, h0, width - h0, t - h0)
                c.drawRoundRect(rect, r, r, ring)
            }
            plus.color = Ui.TEXT; plus.strokeWidth = Ui.px(5f)
            val cx = width / 2f; val cy = t / 2f; val a = Ui.px(16f)
            c.drawLine(cx - a, cy, cx + a, cy, plus); c.drawLine(cx, cy - a, cx, cy + a, plus)
        } else {
            val art = app.art
            if (app.pkg == "com.google.android.youtube.tv") {
                // The app ships a white icon; the launcher shows it as a solid YouTube red tile with a white play mark.
                tilePaint.color = 0xFFFF0000.toInt()
                c.drawRoundRect(rect.also { it.set(0f, 0f, width.toFloat(), t) }, r, r, tilePaint)
                val s = width * 0.17f
                val cx = width / 2f + s * 0.12f; val cy = t / 2f
                play.reset()
                play.moveTo(cx - s * 0.8f, cy - s)
                play.lineTo(cx + s * 1.1f, cy)
                play.lineTo(cx - s * 0.8f, cy + s)
                play.close()
                fill.color = Color.WHITE
                fill.strokeJoin = Paint.Join.ROUND
                fill.strokeWidth = width * 0.05f
                fill.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(play, fill)
            } else if (art != null) {
                // The icon fills the whole rounded tile, like a TV launcher does.
                clip.reset()
                clip.addRoundRect(0f, 0f, width.toFloat(), t, r, r, Path.Direction.CW)
                c.save()
                c.clipPath(clip)
                val ins = width * art.inset
                dst.set(ins, ins, width - ins, t - ins)
                val q = art.quad
                if (isPlay && IconStyle.play == "colors" && q != null) {
                    // Four logo colours split along the two diagonals, so the tile is clean edge to edge.
                    val cx = width / 2f; val cy = t / 2f
                    val dd = width * 2f
                    fill.style = Paint.Style.FILL_AND_STROKE
                    fill.strokeJoin = Paint.Join.MITER
                    fill.strokeWidth = Ui.px(1.6f)   // small overlap so no dark seam shows between colours
                    fun tri(color: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
                        play.reset(); play.moveTo(cx, cy); play.lineTo(x1, y1); play.lineTo(x2, y2); play.close()
                        fill.color = color
                        c.drawPath(play, fill)
                    }
                    tri(q.top, cx - dd, cy - dd, cx + dd, cy - dd)
                    tri(q.left, cx - dd, cy - dd, cx - dd, cy + dd)
                    tri(q.right, cx + dd, cy - dd, cx + dd, cy + dd)
                    tri(q.bottom, cx - dd, cy + dd, cx + dd, cy + dd)
                } else {
                    c.drawBitmap(art.bmp, art.src, dst, bmpPaint)
                }
                c.restore()
            }
        }
        if (isFocused || moving) Ui.drawFocus(c, 0f, 0f, width.toFloat(), t, r)
        if (!showLabel) return
        text.textSize = Ui.px(19f)
        text.typeface = if (moving) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val label = TextUtils.ellipsize(app?.label ?: "All apps", text, width - Ui.px(8f), TextUtils.TruncateAt.END).toString()
        c.drawText(label, width / 2f, t + Ui.px(10f + 19f), text)
    }
}
