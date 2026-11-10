package dev.tvlauncher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import java.util.Random

/**
 * Pure black space: far layer (nebula spots, galaxies, milky way) slides left to right behind a dark
 * overlay, a near star field slides a bit faster, shooting stars cross now and then. Every eighth star
 * twinkles on its own, the nebulae slowly breathe, and a comet glides past every few minutes. Now and then a
 * satellite crosses and, very rarely, a quiet nova flares. Stars sit at random places (no lattice, so no rows).
 * World design units are 1600 x 900; everything is drawn into two tile bitmaps once, then only slid.
 */
class SpaceView(context: Context, private val saver: Boolean = false) : View(context), Choreographer.FrameCallback {

    companion object {
        const val TILE = 2600f          // design units per tile, the world is two tiles (5200)
        const val WORLD = TILE * 2f
        const val DESIGN_H = 900f
        const val FAR_SPEED = WORLD / 1200f   // one full turn in 20 minutes
        const val NEAR_SPEED = TILE / 400f
        const val STAR_GROUPS = 6
        val STAR_SPEEDS = floatArrayOf(0.5f, 0.78f, 0.95f, 1.13f, 1.36f, 1.74f)   // the last group is a few quicker stars
        const val NEB_GROUPS = 3             // nebulae are baked in three sets that breathe out of step
        val NEB_PERIOD = floatArrayOf(170f, 230f, 300f)   // seconds per breath
        val NEB_PHASE = floatArrayOf(0f, 0.37f, 0.71f)
        const val NEB_SCALE = 0.3f           // soft glows need little resolution
        const val OVERLAY = 0.62f            // dark see-through overlay on the far layer
        const val FRAME_MS = 33L             // about 30 fps

        // far tiles, star layers and twinklers are identical for Home and the screensaver: baked once per process, then shared
        private val LOCK = Any()
        private var cached: Baked? = null
    }

    private class Shoot(var on: Boolean = false) {
        var start = 0L; var dur = 1500L; var ang = 0f; var len = 100f; var th = 2f
        var x = 0f; var y = 0f; var dist = 600f
    }

    private var far: Array<Bitmap?> = arrayOfNulls(2)
    private var stars: Array<Bitmap?> = arrayOfNulls(STAR_GROUPS)
    private var neb: Array<Bitmap?> = arrayOfNulls(NEB_GROUPS * 2)
    private var building = false
    private var renderScale = 1f        // bitmap pixels per design unit
    private var viewScale = 1f          // screen pixels per design unit

    private val farPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = Math.round((1f - OVERLAY) * 255f) }
    private val starPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shootPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nebPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Stars that are drawn every frame so each one can twinkle on its own. */
    internal class Twinklers(val n: Int) {
        val x = FloatArray(n); val y = FloatArray(n); val rad = FloatArray(n); val a = FloatArray(n)
        val col = IntArray(n); val grp = IntArray(n); val phase = FloatArray(n); val freq = FloatArray(n)
    }
    /** The part of the sky that does not depend on Home or screensaver mode. */
    internal class Baked(val rs: Float, val far: Array<Bitmap?>, val stars: Array<Bitmap?>, val tw: Twinklers)
    private var twinklers: Twinklers? = null
    private val twSoft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val twCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nus = FloatArray(STAR_GROUPS)

    // comet
    private var cometOn = false
    private var cometStart = 0L; private var cometDur = 26000L
    private var cometAng = 12f; private var cometDist = 1600f; private var cometLen = 450f
    private var cometX = 0f; private var cometY = 0f
    private var nextCometAt = 0L
    private var cometTail: LinearGradient? = null
    private var cometDust: LinearGradient? = null
    private var cometGlow: RadialGradient? = null
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cometPath = android.graphics.Path()
    // rare sky events: a satellite and a quiet nova
    private var satOn = false
    private var satStart = 0L; private var satDur = 12000L
    private var satX0 = 0f; private var satY0 = 0f; private var satX1 = 0f; private var satY1 = 0f; private var satFade = 2f
    private var nextSatAt = 0L
    private var novaOn = false
    private var novaStart = 0L; private var novaX = 0f; private var novaY = 0f
    private var nextNovaAt = 0L
    private val evPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val novaHalo = RadialGradient(0f, 0f, 50f, intArrayOf(0x8CBED7FF.toInt(), 0x3396B9FF.toInt(), 0x0096B9FF.toInt()),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val satGlow = RadialGradient(0f, 0f, 7f, 0xBFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
    private val dst = RectF()
    private val shoots = Array(6) { Shoot() }
    private val shootShaders = arrayOfNulls<LinearGradient>(6)
    private val rnd = Random()
    private val farOffset = rnd.nextFloat() * WORLD
    private val nearOffset = rnd.nextFloat() * TILE

    private var running = false
    private var motion = true
    private var t0 = 0L
    private var lastFrame = 0L
    private var nextShootAt = 0L

    // light parallax: the sky shifts a little with the focused item (far 0.8 percent, near 2 percent)
    private var parFar = 0f
    private var parNear = 0f
    private var parAnim: android.animation.ValueAnimator? = null

    /** [px] is 800 minus the focused item's center x, in design units. */
    fun setParallax(px: Float) {
        parAnim?.cancel()
        val tf = if (motion) px * 0.008f else 0f
        val tn = if (motion) px * 0.02f else 0f
        val f0 = parFar; val n0 = parNear
        if (Math.abs(tf - f0) < 0.2f && Math.abs(tn - n0) < 0.2f) return
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 900
        a.interpolator = android.view.animation.DecelerateInterpolator()
        a.addUpdateListener {
            val v = it.animatedValue as Float
            parFar = f0 + (tf - f0) * v
            parNear = n0 + (tn - n0) * v
            invalidate()
        }
        parAnim = a
        a.start()
    }

    fun setMotion(on: Boolean) {
        motion = on
        if (on) start() else { stop(); parAnim?.cancel(); parFar = 0f; parNear = 0f; invalidate() }
    }

    fun start() {
        if (running || !motion) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        viewScale = h / DESIGN_H
        renderScale = Math.min(viewScale, 1.2f)  // keep tile bitmaps under the GPU texture limit
        if (!building) build()
    }

    // ---------- bake ----------

    private fun build() {
        building = true
        val rs = renderScale
        Thread {
            try {
                val shared = bakeShared(rs)
                // nebulae in three small bitmaps per tile, so each set can breathe at its own pace
                // (these differ between Home and the screensaver, but are small)
                val nb = Array<Bitmap?>(NEB_GROUPS * 2) { idx ->
                    val b = Bitmap.createBitmap(Math.round(TILE * NEB_SCALE), Math.round(DESIGN_H * NEB_SCALE), Bitmap.Config.ARGB_8888)
                    drawNebTile(Canvas(b), idx % 2, idx / 2, NEB_SCALE)
                    b
                }
                post {
                    far = shared.far
                    stars = shared.stars
                    neb = nb
                    twinklers = shared.tw
                    building = false
                    invalidate()
                }
            } catch (e: Throwable) {
                building = false
            }
        }.start()
    }

    /** Far tiles, star layers and twinklers: baked once per process for this scale and shared by Home and the screensaver. */
    private fun bakeShared(rs: Float): Baked = synchronized(LOCK) {
        val old = cached
        if (old != null && old.rs == rs) return old
        val tw = Math.round(TILE * rs)
        val th = Math.round(DESIGN_H * rs)
        val f0 = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val f1 = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        drawFarTile(Canvas(f0), 0, rs)
        drawFarTile(Canvas(f1), 1, rs)
        // star depths, each in its own bitmap so they can drift at slightly different speeds
        val ss = rs * 0.7f
        val sw = Math.round(TILE * ss); val sh = Math.round(DESIGN_H * ss)
        val twList = ArrayList<FloatArray>()
        val st = Array<Bitmap?>(STAR_GROUPS) { g ->
            val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            drawStars(Canvas(b), ss, g, twList)
            b
        }
        val tr = Random(77L)
        val tws = Twinklers(twList.size)
        for ((i, e) in twList.withIndex()) {
            tws.x[i] = e[0]; tws.y[i] = e[1]; tws.rad[i] = e[2]; tws.a[i] = e[3]
            tws.col[i] = Color.rgb(e[4].toInt(), e[5].toInt(), e[6].toInt())
            tws.grp[i] = e[7].toInt()
            tws.phase[i] = tr.nextFloat()
            tws.freq[i] = 0.12f + tr.nextFloat() * 0.22f   // one twinkle every 3 to 8 seconds
        }
        val b = Baked(rs, arrayOf(f0, f1), st, tws)
        cached = b
        b
    }

    private fun drawFarTile(c: Canvas, k: Int, rs: Float) {
        c.scale(rs, rs)
        // all elements of both design tiles, placed in the 5200 wide world, wrapped around
        for (copy in -1..1) {
            for (ti in 0..1) {
                val tile = SkyData.tiles[ti]
                val dx = ti * TILE + copy * WORLD - k * TILE
                drawBand(c, tile.band, dx, ti)
                tile.gal.forEach { drawGalaxy(c, it, dx) }
            }
        }
    }

    /** One breathing set of nebulae (every third one) for tile k, drawn small. */
    private fun drawNebTile(c: Canvas, k: Int, g: Int, rs: Float) {
        c.scale(rs, rs)
        for (copy in -1..1) {
            for (ti in 0..1) {
                val dx = ti * TILE + copy * WORLD - k * TILE
                SkyData.tiles[ti].neb.forEachIndexed { i, n -> if (i % NEB_GROUPS == g) drawNebula(c, n, dx) }
            }
        }
    }

    private val tmp = Matrix()

    private fun drawNebula(c: Canvas, n0: SkyData.Neb, dx0: Float) {
        // home: some spots sit beside their galaxy (n0.dx, n0.dy); screensaver: every spot sits on its galaxy
        val ox = if (saver) 0f else n0.dx
        val oy = if (saver) 0f else n0.dy
        val n = n0
        val dx = dx0 + ox
        val ny = n.y + oy
        val cx = n.x + dx + n.w / 2f
        val cy = ny + n.h / 2f
        if (cx + n.w < 0 || cx - n.w > TILE) return
        val col = n.color
        val a = Math.min(255, Math.round(n.a * 255f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // css: radial-gradient(ellipse at center, color, transparent 66%) => reaches zero at 0.66 * farthest corner
        val sh = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)),
            Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))), floatArrayOf(0f, 0.66f), Shader.TileMode.CLAMP)
        tmp.reset()
        tmp.postScale(n.w / 2f * 1.4142f, n.h / 2f * 1.4142f)
        tmp.postTranslate(cx, cy)
        sh.setLocalMatrix(tmp)
        p.shader = sh
        c.drawRect(n.x + dx - n.w * 0.2f, ny - n.h * 0.2f, n.x + dx + n.w * 1.2f, ny + n.h * 1.2f, p)
    }

    private fun drawGalaxy(c: Canvas, g: SkyData.Gal, dx: Float) {
        val cx = g.x + dx + g.w / 2f
        val cy = g.y + g.h / 2f
        if (cx + g.w < 0 || cx - g.w > TILE) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sh = RadialGradient(0f, 0f, 1f, g.col, g.pos, Shader.TileMode.CLAMP)
        tmp.reset()
        tmp.postScale(g.w / 2f * 1.4142f, g.h / 2f * 1.4142f)
        sh.setLocalMatrix(tmp)
        p.shader = sh
        c.save()
        c.translate(cx, cy)
        c.rotate(g.rot)
        // elliptical element with border-radius 50%: the gradient fades out before the edge
        val oval = RectF(-g.w / 2f, -g.h / 2f, g.w / 2f, g.h / 2f)
        c.drawOval(oval, p)
        c.restore()
    }

    private fun drawBand(c: Canvas, b: SkyData.Band, dx: Float, seed: Int) {
        val cx = b.x + dx + b.w / 2f
        val cy = b.y + b.h / 2f
        if (cx + b.w < 0 || cx - b.w > TILE) return
        val r = Random(900L + seed)
        c.save()
        c.translate(cx, cy)
        c.rotate(b.rot)
        val half = RectF(-b.w / 2f, -b.h / 2f, b.w / 2f, b.h / 2f)
        val layer = c.saveLayer(half.left - 20, half.top - 20, half.right + 20, half.bottom + 20, null)
        // soft glow: purple, pink, blue across the band
        val glow = Paint()
        glow.shader = LinearGradient(0f, half.top, 0f, half.bottom,
            intArrayOf(Color.argb(0, 150, 110, 230), Color.argb(46, 150, 110, 230), Color.argb(42, 255, 200, 215),
                Color.argb(38, 100, 150, 255), Color.argb(0, 100, 150, 255)),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(half, glow)
        // dust: tiny stars, densest in the middle
        val dust = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFD9F2.toInt(), 0xFFCFE0FF.toInt())
        val count = Math.round(b.w * b.h / 1200f)
        for (i in 0 until count) {
            val x = half.left + r.nextFloat() * b.w
            val yn = (r.nextFloat() + r.nextFloat() + r.nextFloat()) / 3f
            val y = half.top + yn * b.h
            val mid = 1f - Math.abs(yn - 0.5f) * 2f
            dust.color = colors[r.nextInt(3)]
            dust.alpha = Math.round(255f * (0.35f + 0.5f * mid) * (0.4f + 0.6f * r.nextFloat()))
            c.drawCircle(x, y, 0.5f + r.nextFloat() * 0.5f, dust)
        }
        // soft ends: fade the left and right 22 percent
        val mask = Paint()
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        mask.shader = LinearGradient(half.left, 0f, half.right, 0f,
            intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.22f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(half.left - 20, half.top - 20, half.right + 20, half.bottom + 20, mask)
        c.restoreToCount(layer)
        c.restore()
    }

    private class StarSpec(
        val x: Float, val y: Float, val r: Float, val a: Float, val col: Int,
        val haloR: Float, val haloA: Float, val group: Int
    )

    /** Every star of the sky, placed at random (seeded, so it looks the same every start). Built once on the bake thread. */
    private val starSpecs: List<StarSpec> by lazy { genStarSpecs() }

    private fun genStarSpecs(): List<StarSpec> {
        val out = ArrayList<StarSpec>()
        val r = Random(20871987L)
        val white = intArrayOf(0xFFFFFF, 0xFFFFFF, 0xCFE0FF, 0xFFE9C8, 0xFFD9F2)
        val colored = intArrayOf(0xFFB060, 0x96CDFF, 0xFF7A70, 0xFFE28C, 0xC49CFF)
        fun opaque(rgb: Int) = rgb or 0xFF000000.toInt()

        // fine field: random places, clustered by a few soft density blobs, wrapping every tile
        val bx = FloatArray(9) { r.nextFloat() * TILE }
        val by = FloatArray(9) { r.nextFloat() * DESIGN_H }
        val brx = FloatArray(9) { 250f + r.nextFloat() * 500f }
        val bry = FloatArray(9) { 150f + r.nextFloat() * 300f }
        val bw = FloatArray(9) { 0.5f + r.nextFloat() }
        fun dens(x: Float, y: Float): Float {
            var d = 0.4f
            for (i in 0 until 9) for (k in -1..1) {
                val dx = (x - bx[i] + k * TILE) / brx[i]
                val dy = (y - by[i]) / bry[i]
                d += bw[i] * Math.exp(-(dx * dx + dy * dy).toDouble()).toFloat()
            }
            return d
        }
        var dmax = 0f
        var gx = 0f
        while (gx < TILE) { var gy = 0f; while (gy < DESIGN_H) { dmax = Math.max(dmax, dens(gx, gy)); gy += 40f }; gx += 40f }
        var n = 0
        while (n < 2200) {
            val x = 3f + r.nextFloat() * (TILE - 6f)
            val y = 3f + r.nextFloat() * (DESIGN_H - 6f)
            if (r.nextFloat() * dmax > dens(x, y)) continue
            val rad = 0.45f + Math.pow(r.nextFloat().toDouble(), 3.0).toFloat() * 0.7f
            val al = 0.35f + Math.pow(r.nextFloat().toDouble(), 2.0).toFloat() * 0.6f
            out.add(StarSpec(x, y, rad, al, opaque(white[r.nextInt(white.size)]), 0f, 0f, r.nextInt(4)))
            n++
        }

        // brighter and colored stars, spread out so none line up in a row
        fun place(count: Int, minD: Float, seed: List<FloatArray>): ArrayList<FloatArray> {
            val res = ArrayList<FloatArray>()
            var tries = 0
            while (res.size < count && tries < 20000) {
                tries++
                val x = 22f + r.nextFloat() * (TILE - 44f)
                val y = 22f + r.nextFloat() * (DESIGN_H - 44f)
                var ok = true
                for (q in seed + res) {
                    var dx = Math.abs(x - q[0]); dx = Math.min(dx, TILE - dx)
                    val dy = y - q[1]
                    if (dx * dx + dy * dy < minD * minD) { ok = false; break }
                }
                if (ok && res.count { Math.abs(it[1] - y) < 14f } >= 2) ok = false
                if (ok) res.add(floatArrayOf(x, y))
            }
            return res
        }
        val bright = place(46, 60f, emptyList())
        for ((i, p) in bright.withIndex()) {
            if (i < 7) {
                val c = opaque(colored[i % colored.size])
                val rad = floatArrayOf(1.8f, 2f, 2.2f)[r.nextInt(3)]
                out.add(StarSpec(p[0], p[1], rad, 1f, c, 11f, 0.24f, 4))
            } else {
                val k = r.nextFloat()
                val c = opaque(white[r.nextInt(white.size)])
                when {
                    k < 0.12f -> out.add(StarSpec(p[0], p[1], 2.5f, 0.95f, c, 14f, 0.2f, 4))
                    k < 0.30f -> out.add(StarSpec(p[0], p[1], 2f, 0.95f, c, 11f, 0.16f, 4))
                    else -> out.add(StarSpec(p[0], p[1], 1.5f, 0.95f, c, 0f, 0f, 4))
                }
            }
        }
        // a few quicker stars on their own layer
        val quickCols = intArrayOf(0xFFFFFF, 0xCFE0FF, 0xFFFFFF, 0xFFE9C8)
        for (p in place(11, 150f, bright)) {
            out.add(StarSpec(p[0], p[1], floatArrayOf(1.6f, 1.8f, 2.2f)[r.nextInt(3)], 0.95f,
                opaque(quickCols[r.nextInt(quickCols.size)]), 10f, 0.16f, 5))
        }
        return out
    }

    private fun drawStars(c: Canvas, rs: Float, group: Int, twOut: ArrayList<FloatArray>) {
        c.scale(rs, rs)
        val soft = Paint(Paint.ANTI_ALIAS_FLAG)
        val core = Paint(Paint.ANTI_ALIAS_FLAG)
        val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        var count = 0
        for (s in starSpecs) {
            if (s.group != group) continue
            val cr = Color.red(s.col); val cg = Color.green(s.col); val cb = Color.blue(s.col)
            if (s.haloR > 0f) {
                halo.shader = RadialGradient(s.x, s.y, s.haloR, Color.argb(Math.round(s.haloA * 255f), cr, cg, cb),
                    Color.argb(0, cr, cg, cb), Shader.TileMode.CLAMP)
                c.drawCircle(s.x, s.y, s.haloR, halo)
            }
            if (group < 4) {
                count++
                if (count % 12 == 0) {   // about one in twelve twinkles on its own
                    twOut.add(floatArrayOf(s.x, s.y, s.r / 0.8f, s.a, cr.toFloat(), cg.toFloat(), cb.toFloat(), group.toFloat()))
                    continue
                }
                soft.color = Color.argb(Math.round(s.a * 255f * 0.3f), cr, cg, cb)
                core.color = Color.argb(Math.round(s.a * 255f), cr, cg, cb)
                c.drawCircle(s.x, s.y, s.r * 1.8f, soft)
                c.drawCircle(s.x, s.y, s.r, core)
            } else {
                soft.color = Color.argb(Math.round(s.a * 255f * 0.5f), cr, cg, cb)
                core.color = Color.argb(Math.round(s.a * 255f), cr, cg, cb)
                c.drawCircle(s.x, s.y, s.r, soft)
                c.drawCircle(s.x, s.y, s.r * 0.55f, core)
            }
        }
    }

    // ---------- frames ----------

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val now = frameTimeNanos / 1_000_000L
        if (now - lastFrame < FRAME_MS) return
        lastFrame = now
        if (t0 == 0L) {
            t0 = now; nextShootAt = now + 1200; nextCometAt = now + 30_000L
            nextSatAt = now + 300_000L + rnd.nextInt(420_000)        // first satellite in 5 to 12 minutes
            nextNovaAt = now + 900_000L + rnd.nextInt(1_500_000)     // first nova in 15 to 40 minutes
        }
        stepShoots(now)
        stepComet(now)
        stepEvents(now)
        invalidate()
    }

    private fun stepShoots(now: Long) {
        if (now >= nextShootAt) {
            val s = shoots.indexOfFirst { !it.on }
            if (s >= 0) {
                val sh = shoots[s]
                val right = rnd.nextBoolean()
                val ang = if (right) 18f + rnd.nextFloat() * 20f else 180f - (18f + rnd.nextFloat() * 20f)
                sh.on = true; sh.start = now; sh.dur = 1100L + rnd.nextInt(900); sh.ang = ang
                sh.len = 60f + rnd.nextFloat() * 180f; sh.dist = 450f + rnd.nextFloat() * 350f
                sh.th = 1f + rnd.nextFloat() * 2f
                sh.x = if (right) rnd.nextFloat() * 1000f else 600f + rnd.nextFloat() * 1000f
                sh.y = 20f + rnd.nextFloat() * 500f
                shootShaders[s] = LinearGradient(-sh.len / 2f, 0f, sh.len / 2f, 0f,
                    0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
            }
            nextShootAt = now + 900 + rnd.nextInt(2500)
        }
    }

    private fun stepEvents(now: Long) {
        if (!satOn && now >= nextSatAt) {
            satOn = true; satStart = now; satDur = 11_000L + rnd.nextInt(5_000)
            val toRight = rnd.nextBoolean()
            satX0 = if (toRight) -20f else 1620f; satX1 = if (toRight) 1620f else -20f
            satY0 = 60f + rnd.nextFloat() * 320f
            satY1 = satY0 - 120f + rnd.nextFloat() * 280f
            satFade = if (rnd.nextBoolean()) 0.5f + rnd.nextFloat() * 0.3f else 2f   // half of them slip into Earth's shadow
            nextSatAt = now + 480_000L + rnd.nextInt(720_000)        // then every 8 to 20 minutes
        }
        if (!novaOn && now >= nextNovaAt) {
            novaOn = true; novaStart = now
            novaX = 120f + rnd.nextFloat() * 1360f; novaY = 40f + rnd.nextFloat() * 520f
            nextNovaAt = now + 1_500_000L + rnd.nextInt(2_100_000)   // then every 25 to 60 minutes
        }
    }

    private fun stepComet(now: Long) {
        if (cometOn || now < nextCometAt) return
        cometOn = true
        cometStart = now
        cometDur = 24_000L + rnd.nextInt(10_000)          // a slow glide across the screen
        val right = rnd.nextBoolean()
        val tilt = 6f + rnd.nextFloat() * 10f
        cometAng = if (right) tilt else 180f - tilt
        cometDist = 1500f + rnd.nextFloat() * 300f
        cometLen = 380f + rnd.nextFloat() * 140f
        cometX = if (right) -120f else 1720f
        cometY = 60f + rnd.nextFloat() * 240f
        cometTail = LinearGradient(0f, 0f, -cometLen, 0f, 0xE8EAF6FF.toInt(), 0x00BFE3FF, Shader.TileMode.CLAMP)
        cometDust = LinearGradient(0f, 0f, -cometLen * 0.8f, 0f, 0x66A8C8FF, 0x00A8C8FF, Shader.TileMode.CLAMP)
        if (cometGlow == null) cometGlow = RadialGradient(0f, 0f, 15f, 0xCCFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        nextCometAt = now + 150_000L + rnd.nextInt(150_000)   // then every 2.5 to 5 minutes
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val fb0 = far[0]; val fb1 = far[1]
        if (fb0 == null || fb1 == null || stars.any { it == null } || neb.any { it == null }) return
        val ms = if (t0 == 0L) 0L else (lastFrame - t0)
        val sec = if (motion) ms / 1000f else 0f
        val fu = (farOffset + sec * FAR_SPEED) % WORLD

        canvas.save()
        canvas.scale(viewScale, viewScale)
        val viewW = width / viewScale
        canvas.translate(parFar, 0f)
        // nebulae: three sets, each breathing slowly at its own pace
        val farAlpha = (1f - OVERLAY) * 255f
        for (g in 0 until NEB_GROUPS) {
            val s = 0.5f + 0.5f * Math.sin(2.0 * Math.PI * (sec / NEB_PERIOD[g] + NEB_PHASE[g])).toFloat()
            nebPaint.alpha = Math.min(255, Math.round(farAlpha * (0.7f + 0.45f * s)))
            for (k in 0..1) {
                val nbmp = neb[g * 2 + k] ?: continue
                for (m in -1..1) {
                    val x = k * TILE + fu + m * WORLD - WORLD / 2f
                    if (x > viewW || x + TILE < 0) continue
                    dst.set(x, 0f, x + TILE, DESIGN_H)
                    canvas.drawBitmap(nbmp, null, dst, nebPaint)
                }
            }
        }
        for (k in 0..1) {
            val bmp = if (k == 0) fb0 else fb1
            for (m in -1..1) {
                val x = k * TILE + fu + m * WORLD - WORLD / 2f
                if (x > viewW || x + TILE < 0) continue
                dst.set(x, 0f, x + TILE, DESIGN_H)
                canvas.drawBitmap(bmp, null, dst, farPaint)
            }
        }
        canvas.translate(parNear - parFar, 0f)
        // near stars: steady; the twinkling ones are drawn one by one below
        starPaint.alpha = Math.round(255f * 0.7f)
        for (g in 0 until STAR_GROUPS) {
            // far-ish stars drift a little slower, close ones a little faster
            val sp = STAR_SPEEDS[g]
            val nu = (nearOffset * sp + sec * NEAR_SPEED * sp) % TILE
            nus[g] = nu
            val sb = stars[g] ?: continue
            var x = nu - TILE
            while (x < viewW) {
                dst.set(x, 0f, x + TILE, DESIGN_H)
                canvas.drawBitmap(sb, null, dst, starPaint)
                x += TILE
            }
        }
        // stars that twinkle on their own
        val tws = twinklers
        if (tws != null) {
            for (i in 0 until tws.n) {
                val s = 0.5f + 0.5f * Math.sin(2.0 * Math.PI * (sec * tws.freq[i] + tws.phase[i])).toFloat()
                val f = 0.2f + 1.05f * s * s
                val al = Math.min(255, Math.round(255f * tws.a[i] * 0.9f * f))
                if (al < 4) continue
                val c0 = tws.col[i]
                twCore.color = Color.argb(al, Color.red(c0), Color.green(c0), Color.blue(c0))
                twSoft.color = Color.argb(Math.round(al * 0.35f), Color.red(c0), Color.green(c0), Color.blue(c0))
                var x = nus[tws.grp[i]] - TILE
                while (x < viewW) {
                    val px = x + tws.x[i]
                    if (px > -6f && px < viewW + 6f) {
                        canvas.drawCircle(px, tws.y[i], tws.rad[i] * 1.2f, twSoft)
                        canvas.drawCircle(px, tws.y[i], tws.rad[i] * 0.65f, twCore)
                    }
                    x += TILE
                }
            }
        }
        canvas.translate(-parNear, 0f)
        // shooting stars
        for (i in shoots.indices) {
            val s = shoots[i]
            if (!s.on) continue
            val p = (lastFrame - s.start).toFloat() / s.dur
            if (p >= 1f) { s.on = false; continue }
            val r = Math.toRadians(s.ang.toDouble())
            val cx = s.x + Math.cos(r).toFloat() * s.dist * p
            val cy = s.y + Math.sin(r).toFloat() * s.dist * p
            shootPaint.shader = shootShaders[i]
            shootPaint.alpha = Math.round(255f * Math.min(1f, p * 8f) * Math.min(1f, (1f - p) * 3f))
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(s.ang)
            canvas.drawRect(-s.len / 2f, -s.th / 2f, s.len / 2f, s.th / 2f, shootPaint)
            canvas.restore()
        }
        // comet: long soft tail, slow glide
        if (cometOn) {
            val p = (lastFrame - cometStart).toFloat() / cometDur
            if (p >= 1f) {
                cometOn = false
            } else {
                val r = Math.toRadians(cometAng.toDouble())
                val hx = cometX + Math.cos(r).toFloat() * cometDist * p
                val hy = cometY + Math.sin(r).toFloat() * cometDist * p
                val env = Math.min(1f, Math.min(p * 8f, (1f - p) * 6f))
                canvas.save()
                canvas.translate(hx, hy)
                canvas.rotate(cometAng)
                cometPaint.shader = cometDust
                cometPaint.alpha = Math.round(255f * env)
                cometPath.reset(); cometPath.moveTo(0f, -7f); cometPath.lineTo(-cometLen * 0.8f, 0f); cometPath.lineTo(0f, 7f); cometPath.close()
                canvas.drawPath(cometPath, cometPaint)
                cometPaint.shader = cometTail
                cometPaint.alpha = Math.round(255f * env)
                cometPath.reset(); cometPath.moveTo(0f, -1.8f); cometPath.lineTo(-cometLen, 0f); cometPath.lineTo(0f, 1.8f); cometPath.close()
                canvas.drawPath(cometPath, cometPaint)
                cometPaint.shader = cometGlow
                cometPaint.alpha = Math.round(255f * env)
                canvas.drawCircle(0f, 0f, 15f, cometPaint)
                cometPaint.shader = null
                cometPaint.color = Color.argb(Math.round(255f * env), 255, 255, 255)
                canvas.drawCircle(0f, 0f, 2.2f, cometPaint)
                canvas.restore()
            }
        }
        // satellite: one small dot, steady path, slow glint, sometimes lost in Earth's shadow
        if (satOn) {
            val p = (lastFrame - satStart).toFloat() / satDur
            if (p >= 1f) {
                satOn = false
            } else {
                val x = satX0 + (satX1 - satX0) * p
                val y = satY0 + (satY1 - satY0) * p
                val edge = Math.min(1f, Math.min(p / 0.12f, (1f - p) / 0.12f))
                val glint = 0.55f + 0.45f * Math.sin(((lastFrame - satStart) / 1000f * 3.4f).toDouble()).toFloat()
                val shadow = if (p > satFade) Math.max(0f, 1f - (p - satFade) / 0.05f) else 1f
                val al = Math.min(1f, edge * 4f) * glint * shadow
                if (al > 0.01f) {
                    canvas.save()
                    canvas.translate(x, y)
                    evPaint.shader = satGlow
                    evPaint.alpha = Math.round(255f * al)
                    canvas.drawCircle(0f, 0f, 7f, evPaint)
                    evPaint.shader = null
                    evPaint.color = Color.argb(Math.round(255f * al), 255, 255, 255)
                    canvas.drawCircle(0f, 0f, 1.5f, evPaint)
                    canvas.restore()
                }
            }
        }
        // quiet nova: a star flares fast, then fades while a soft halo grows
        if (novaOn) {
            val p = (lastFrame - novaStart).toFloat() / 6500f
            if (p >= 1f) {
                novaOn = false
            } else {
                val rise = Math.min(1f, p / 0.08f)
                val decay = Math.exp(-(Math.max(0f, p - 0.08f) * 5.2f).toDouble()).toFloat()
                val al = rise * decay
                if (al > 0.01f) {
                    canvas.save()
                    canvas.translate(novaX, novaY)
                    val hs = 0.15f + 1.35f * Math.sqrt(p.toDouble()).toFloat()
                    canvas.scale(hs, hs)
                    evPaint.shader = novaHalo
                    evPaint.alpha = Math.round(255f * al)
                    canvas.drawCircle(0f, 0f, 50f, evPaint)
                    canvas.restore()
                    evPaint.shader = satGlow
                    canvas.save()
                    canvas.translate(novaX, novaY)
                    canvas.drawCircle(0f, 0f, 7f, evPaint)
                    evPaint.shader = null
                    evPaint.color = Color.argb(Math.round(255f * al), 255, 255, 255)
                    canvas.drawCircle(0f, 0f, 3f * (0.6f + 0.8f * decay), evPaint)
                    canvas.restore()
                }
            }
        }
        canvas.restore()
    }
}
