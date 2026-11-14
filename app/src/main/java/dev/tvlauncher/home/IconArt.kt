package dev.tvlauncher.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/**
 * An app icon prepared for a tile: the icon is rendered once, cropped to its visible art, and given a
 * background that matches its edge, so it fills the rounded tile. Icons that are mostly transparent
 * (a bare logo) sit on a neutral card with a small inset instead.
 */
/** Recolours a light icon: dark parts become [darkTo], light parts [lightTo]. [byLuma] decides "dark" by brightness instead of the green channel. [bg] forces the tile colour. */
class Recolor(val darkTo: Int, val lightTo: Int, val byLuma: Boolean = false, val bg: Int = 0)

/** Four colours and the centre of the Play Store logo, so a tile can continue its colours to the corners. */
class QuadColors(val top: Int, val left: Int, val right: Int, val bottom: Int, val cx: Float, val cy: Float)

class IconArt(val bmp: Bitmap, val src: Rect, val bg: Int, val inset: Float, val quad: QuadColors? = null) {
    companion object {
        private const val N = 320
        private const val LIGHT = 0xFFF1F3F4.toInt()
        private const val DARK = 0xFF1A1C24.toInt()

        fun build(d: Drawable?, rc: Recolor? = null, wantQuad: Boolean = false, darkBg: Boolean = false, rejectFull: Boolean = false): IconArt? {
            if (d == null) return null
            return try {
                val bmp = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                if (d is AdaptiveIconDrawable) {
                    // 108 unit canvas, middle 72 visible: draw the layers 1.5x so the visible part fills the bitmap.
                    val e = N / 4
                    if (!darkBg) d.background?.let { it.setBounds(-e, -e, N + e, N + e); it.draw(c) }
                    d.foreground?.let { it.setBounds(-e, -e, N + e, N + e); it.draw(c) }
                } else {
                    d.setBounds(0, 0, N, N)
                    d.draw(c)
                }
                val px = IntArray(N * N)
                bmp.getPixels(px, 0, N, 0, 0, N, N)
                if (rc != null) {
                    for (i in px.indices) {
                        val p = px[i]
                        val a = Color.alpha(p)
                        if (a == 0) continue
                        val f = if (rc.byLuma)
                            Math.min(1f, ((0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f) / 0.3f)
                        else Color.green(p) / 255f
                        val r = Color.red(rc.darkTo) + (Color.red(rc.lightTo) - Color.red(rc.darkTo)) * f
                        val g = Color.green(rc.darkTo) + (Color.green(rc.lightTo) - Color.green(rc.darkTo)) * f
                        val b = Color.blue(rc.darkTo) + (Color.blue(rc.lightTo) - Color.blue(rc.darkTo)) * f
                        px[i] = Color.argb(a, Math.round(r), Math.round(g), Math.round(b))
                    }
                    bmp.setPixels(px, 0, N, 0, 0, N, N)
                }
                var x0 = N; var y0 = N; var x1 = -1; var y1 = -1
                for (y in 0 until N) for (x in 0 until N) {
                    if (Color.alpha(px[y * N + x]) > 40) {
                        if (x < x0) x0 = x; if (x > x1) x1 = x
                        if (y < y0) y0 = y; if (y > y1) y1 = y
                    }
                }
                if (x1 < 0) return null
                val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
                var opaque = 0L; var lum = 0.0
                for (y in y0..y1) for (x in x0..x1) {
                    val p = px[y * N + x]
                    if (Color.alpha(p) > 40) {
                        opaque++
                        lum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0
                    }
                }
                val coverage = opaque.toDouble() / (bw.toLong() * bh)
                val side = Math.min(N, Math.max(bw, bh))
                val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
                val sl = (cx - side / 2).coerceIn(0, N - side)
                val st = (cy - side / 2).coerceIn(0, N - side)
                val src = Rect(sl, st, sl + side, st + side)
                // rejectFull: a flat recolour of an icon that fills its whole square would be just a coloured square
                if (rejectFull && coverage >= 0.85) return null
                if (rc != null && rc.bg != 0) return IconArt(bmp, src, rc.bg, 0.16f)
                if (darkBg) return IconArt(bmp, src, 0xFF000000.toInt(), 0.14f)
                var quad: QuadColors? = null
                if (wantQuad) {
                    fun at(nx: Float, ny: Float): Int = px[(y0 + (ny * bh).toInt()).coerceIn(0, N - 1) * N + (x0 + (nx * bw).toInt()).coerceIn(0, N - 1)]
                    val ct = at(0.35f, 0.21f); val cl = at(0.15f, 0.5f); val cr = at(0.72f, 0.5f); val cb = at(0.35f, 0.78f)
                    if (listOf(ct, cl, cr, cb).all { Color.alpha(it) > 200 })
                        quad = QuadColors(ct, cl, cr, cb, x0 + 0.49f * bw, y0 + 0.47f * bh)
                }
                if (coverage >= 0.78) {
                    // Fill: background is the colour found at the middle of each edge of the art.
                    val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
                    val ix = Math.max(2, bw / 30); val iy = Math.max(2, bh / 30)
                    val pts = arrayOf(intArrayOf(x0 + ix, my), intArrayOf(x1 - ix, my), intArrayOf(mx, y0 + iy), intArrayOf(mx, y1 - iy))
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (pt in pts) {
                        val p = px[pt[1] * N + pt[0]]
                        if (Color.alpha(p) > 200) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++ }
                    }
                    val bg = if (n > 0) Color.rgb(r / n, g / n, b / n) else AppEntry.dominant(d)
                    IconArt(bmp, src, bg, 0f)
                } else {
                    val meanLum = lum / Math.max(1L, opaque)
                    IconArt(bmp, src, if (meanLum < 0.75) LIGHT else DARK, 0.16f, quad)
                }
            } catch (e: Throwable) {
                null
            }
        }
    }
}
