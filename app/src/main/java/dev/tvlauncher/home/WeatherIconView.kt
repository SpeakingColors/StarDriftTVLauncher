package dev.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Draws the weather icons of the mockup on a 64 x 64 grid. */
class WeatherIconView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var kind: Weather.Kind = Weather.Kind.CLOUD
    private var night = false
    private var sunColor = 0xFFFFD84D.toInt()
    private var cloudColor = 0xFFF4F7FB.toInt()
    private var phase = 0.0

    fun set(k: Weather.Kind, isNight: Boolean, toHorizon: Double) {
        kind = k; night = isNight
        sunColor = sunColorFor(toHorizon)
        cloudColor = if (night) 0xFFAEB8D0.toInt() else if (toHorizon < 1.2) 0xFFF6D3C6.toInt() else 0xFFF4F7FB.toInt()
        val jd = System.currentTimeMillis() / 86400000.0 + 2440587.5
        phase = (((jd - 2451550.1) / 29.530588853) % 1.0 + 1.0) % 1.0
        invalidate()
    }

    private fun sunColorFor(h: Double): Int {
        val stops = arrayOf(0.0 to 0xFFFF2E2E.toInt(), 0.5 to 0xFFFF5A2B.toInt(), 1.2 to 0xFFFF9A35.toInt(),
            2.5 to 0xFFFFC83D.toInt(), 4.0 to 0xFFFFD84D.toInt())
        for (i in 0 until stops.size - 1) {
            if (h <= stops[i + 1].first) {
                val f = ((h - stops[i].first) / (stops[i + 1].first - stops[i].first)).toFloat()
                return Ui.mix(stops[i].second, stops[i + 1].second, f.coerceIn(0f, 1f))
            }
        }
        return stops.last().second
    }

    override fun onDraw(c: Canvas) {
        val k = width / 64f
        c.save()
        c.scale(k, k)
        // After sunset the moon is always there, behind the clouds or weather.
        if (night && kind != Weather.Kind.CLEAR && kind != Weather.Kind.PARTLY) moon(c, 27f, 19f, 0.62f)
        when (kind) {
            Weather.Kind.CLEAR -> if (night) moon(c, 32f, 32f, 1f) else sun(c, 32f, 32f, 11f, 8, 3.5f, 24f)
            Weather.Kind.PARTLY -> {
                if (night) moon(c, 25f, 23f, 0.7f) else sun(c, 24f, 24f, 9f, 4, 3f, 17f)
                cloud(c, 4f, 4f, 1f)
            }
            Weather.Kind.RAIN, Weather.Kind.STORM -> {
                cloud(c, 0f, -8f, 1f)
                line.color = 0xFF7AA2FF.toInt(); line.strokeWidth = 3.5f
                c.drawLine(22f, 47f, 19f, 55f, line); c.drawLine(33f, 47f, 30f, 55f, line); c.drawLine(44f, 47f, 41f, 55f, line)
            }
            Weather.Kind.SNOW -> {
                cloud(c, 0f, -8f, 1f)
                fill.color = Color.WHITE
                c.drawCircle(21f, 49f, 3f, fill); c.drawCircle(32f, 55f, 3f, fill); c.drawCircle(43f, 49f, 3f, fill)
            }
            Weather.Kind.FOG -> {
                cloud(c, 0f, -6f, 1f)
                line.color = cloudColor; line.strokeWidth = 3.5f
                c.drawLine(16f, 50f, 48f, 50f, line); c.drawLine(22f, 57f, 54f, 57f, line)
            }
            else -> cloud(c, 0f, 0f, 1f)
        }
        c.restore()
    }

    private fun cloud(c: Canvas, dx: Float, dy: Float, sc: Float) {
        fill.color = cloudColor
        c.save(); c.translate(dx, dy); c.scale(sc, sc)
        c.drawCircle(22f, 42f, 10f, fill)
        c.drawCircle(35f, 34f, 13f, fill)
        c.drawCircle(46f, 41f, 10.5f, fill)
        c.drawRoundRect(RectF(22f, 38f, 46f, 52f), 6f, 6f, fill)
        c.restore()
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, rays: Int, sw: Float, reach: Float) {
        fill.color = sunColor
        c.drawCircle(cx, cy, r, fill)
        line.color = sunColor; line.strokeWidth = sw
        for (i in 0 until rays) {
            val a = Math.PI * 2 * i / rays + (if (rays == 4) Math.PI / 4 else 0.0)
            val x0 = cx + Math.cos(a).toFloat() * (r + 6f); val y0 = cy + Math.sin(a).toFloat() * (r + 6f)
            val x1 = cx + Math.cos(a).toFloat() * (r + 12f); val y1 = cy + Math.sin(a).toFloat() * (r + 12f)
            c.drawLine(x0, y0, x1, y1, line)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, sc: Float) {
        c.save(); c.translate(cx, cy); c.scale(sc, sc)
        fill.color = 0xFF2B3160.toInt()
        c.drawCircle(0f, 0f, 20f, fill)
        val ang = phase * 2 * Math.PI
        val mrx = Math.max(0.01, Math.abs(Math.cos(ang)) * 20).toFloat()
        val waxing = phase < 0.5
        val crescent = phase < 0.25 || phase > 0.75
        val termClockwise = if (waxing) !crescent else crescent
        val p = Path()
        p.moveTo(0f, -20f)
        p.arcTo(RectF(-20f, -20f, 20f, 20f), -90f, if (waxing) 180f else -180f)
        p.arcTo(RectF(-mrx, -20f, mrx, 20f), 90f, if (termClockwise) 180f else -180f)
        p.close()
        fill.color = 0xFFF6F2E2.toInt()
        c.drawPath(p, fill)
        c.restore()
    }
}
