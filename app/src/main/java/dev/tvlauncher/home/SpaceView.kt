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
        const val OVERLAY = 0.62f            // dark see-through overlay on the far layer
        const val FRAME_MS = 33L             // about 30 fps
        val CLASS_SPEEDS = floatArrayOf(0.55f, 0.85f, 1.25f)   // galaxy depth classes drift at different speeds
        const val BAND_SCALE = 0.6f          // the milky way is the only big picture left

        // Animated tiles (Disney+ and co.) ride on the sky's frames: while the sky is ticking they redraw only when it does,
        // so the screen is composed 30 times a second, not 60 (every extra frame replays the whole sky on the render thread).
        @JvmField @Volatile var driveUntil = 0L
        @JvmField val animTiles = java.util.WeakHashMap<View, Long>()

        // far tiles, star layers and twinklers are identical for Home and the screensaver: baked once per process, then shared
        private val LOCK = Any()
        private var cached: Baked? = null
    }

    private class Shoot(var on: Boolean = false) {
        var start = 0L; var dur = 1500L; var ang = 0f; var len = 100f; var th = 2f
        var x = 0f; var y = 0f; var dist = 600f
    }

    private var far: Array<Bitmap?> = arrayOfNulls(2)                     // milky way, one bitmap per tile
    // Only the milky way is a big picture. Everything else is drawn where it is, so the GPU never fills a whole screen for a few dots.
    private var gals: Array<Array<GalDraw>>? = null
    private var boxes: Array<Array<BoxDraw>>? = null
    private var groups: Array<StarGroup>? = null
    private var nebs: Array<Array<NebDraw>>? = null
    private var building = false
    private var renderScale = 1f        // bitmap pixels per design unit
    private var viewScale = 1f          // screen pixels per design unit

    private val farPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = Math.round((1f - OVERLAY) * 255f) }
    private val shootPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Stars that are drawn every frame so each one can twinkle on its own. */
    internal class Twinklers(val n: Int) {
        val x = FloatArray(n); val y = FloatArray(n); val rad = FloatArray(n); val a = FloatArray(n)
        val col = IntArray(n); val grp = IntArray(n); val phase = FloatArray(n); val freq = FloatArray(n)
    }
    /** The part of the sky that does not depend on Home or screensaver mode. */
    internal class GalDraw(val bx: Float, val cy: Float, val w: Float, val h: Float, val rot: Float, val ext: Float, val paint: Paint)
    internal class BoxDraw(val bx: Float, val cy: Float, val hw: Float, val hh: Float, val rot: Float, val ext: Float, val spr: Bitmap)
    internal class Bucket(val xs: FloatArray, val pts: FloatArray, val soft: Paint?, val core: Paint)
    internal class BigStar(val x: Float, val y: Float, val r: Float, val haloR: Float, val halo: Paint?, val soft: Paint, val core: Paint)
    internal class StarGroup(val buckets: Array<Bucket>, val big: Array<BigStar>)
    private class NebDraw(val bx: Float, val cy: Float, val w: Float, val h: Float, val ext: Float, val paint: Paint)
    internal class Baked(val rs: Float, val far: Array<Bitmap?>, val gals: Array<Array<GalDraw>>, val boxes: Array<Array<BoxDraw>>,
                         val groups: Array<StarGroup>, val tw: Twinklers)
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

    // Focus kick: when the focus moves (either way) the sky briefly drifts faster, then settles back to its own pace.
    // It only ever adds to the drift, so left and right both speed it up and it never slows or stops.
    private var boost = 0f           // seconds of extra drift applied so far
    private var boostTarget = 0f
    private var lastPx = Float.NaN
    private var boostAnim: android.animation.ValueAnimator? = null

    /** [px] is 800 minus the focused item's center x, in design units. */
    fun setParallax(px: Float) {
        val d = if (lastPx.isNaN()) 0f else Math.abs(px - lastPx)
        lastPx = px
        if (!motion || d < 0.2f) return
        boostAnim?.cancel()
        boostTarget += d * 0.0009f
        val b0 = boost; val tg = boostTarget
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 900
        a.interpolator = android.view.animation.DecelerateInterpolator()
        a.addUpdateListener {
            boost = b0 + (tg - b0) * (it.animatedValue as Float)
            if (!running) invalidate()   // while the sky ticks it redraws at 30 fps anyway
        }
        boostAnim = a
        a.start()
    }

    fun setMotion(on: Boolean) {
        motion = on
        if (on) start() else { stop(); boostAnim?.cancel(); invalidate() }
    }

    fun start() {
        if (running || !motion) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        driveUntil = 0L
        tickTiles()   // the tiles go back to driving themselves
    }

    /** Redraws the animated tiles that drew recently; forgets the ones that are gone. */
    private fun tickTiles() {
        if (animTiles.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        val it = animTiles.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value < 250L) e.key.invalidate() else it.remove()
        }
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
                val nd = buildNebs()
                post {
                    far = shared.far
                    gals = shared.gals
                    boxes = shared.boxes
                    groups = shared.groups
                    nebs = nd
                    twinklers = shared.tw
                    building = false
                    invalidate()
                }
            } catch (e: Throwable) {
                building = false
            }
        }.start()
    }

    /** Milky way pictures, galaxy sprites, star points and twinklers: made once per process for this scale and shared by Home and the screensaver. */
    private fun bakeShared(rs: Float): Baked = synchronized(LOCK) {
        val old = cached
        if (old != null && old.rs == rs) return old
        val bs = rs * BAND_SCALE
        val f0 = Bitmap.createBitmap(Math.round(TILE * bs), Math.round(DESIGN_H * bs), Bitmap.Config.ARGB_8888)
        val f1 = Bitmap.createBitmap(Math.round(TILE * bs), Math.round(DESIGN_H * bs), Bitmap.Config.ARGB_8888)
        drawBandTile(Canvas(f0), 0, bs)
        drawBandTile(Canvas(f1), 1, bs)
        val data = SkyNew.get(context)
        val ka = 1f - OVERLAY   // the far layer sits behind a dark see-through overlay
        val gl = Array(NEB_GROUPS) { c ->
            val out = ArrayList<GalDraw>()
            for (ti in 0..1) for (g in data[c].tiles[ti].gal) {
                val cols = IntArray(g.col.size) { i ->
                    Color.argb(Math.round(Color.alpha(g.col[i]) * ka), Color.red(g.col[i]), Color.green(g.col[i]), Color.blue(g.col[i]))
                }
                val sh = RadialGradient(0f, 0f, 1f, cols, g.pos, Shader.TileMode.CLAMP)
                val m = Matrix()
                m.setScale(g.w / 2f * 1.4142f, g.h / 2f * 1.4142f)
                sh.setLocalMatrix(m)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = sh
                out.add(GalDraw(ti * TILE + g.x + g.w / 2f, g.y + g.h / 2f, g.w, g.h, g.rot, Math.max(g.w, g.h) / 2f + 4f, p))
            }
            out.toTypedArray()
        }
        val bx = Array(NEB_GROUPS) { c ->
            val out = ArrayList<BoxDraw>()
            for (ti in 0..1) for (b in data[c].tiles[ti].box) out.add(makeBox(b, ti, rs))
            out.toTypedArray()
        }
        val twList = ArrayList<FloatArray>()
        val sg = Array(STAR_GROUPS) { g -> buildGroup(g, twList) }
        val tr = Random(77L)
        val tws = Twinklers(twList.size)
        for ((i, e) in twList.withIndex()) {
            tws.x[i] = e[0]; tws.y[i] = e[1]; tws.rad[i] = e[2]; tws.a[i] = e[3]
            tws.col[i] = Color.rgb(e[4].toInt(), e[5].toInt(), e[6].toInt())
            tws.grp[i] = e[7].toInt()
            tws.phase[i] = tr.nextFloat()
            tws.freq[i] = 0.12f + tr.nextFloat() * 0.22f   // one twinkle every 3 to 8 seconds
        }
        val b = Baked(rs, arrayOf(f0, f1), gl, bx, sg, tws)
        cached = b
        b
    }

    /** The milky way: two pre-rendered pieces (assets), one per design tile, placed like the old band and tilted -22 degrees. */
    private fun drawBandTile(c: Canvas, k: Int, rs: Float) {
        c.scale(rs, rs)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (ti in 0..1) {
            val bmp = try {
                context.assets.open(if (ti == 0) "sky_band_a.webp" else "sky_band_b.webp").use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (e: Throwable) { null } ?: continue
            val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
            for (copy in -1..1) {
                val dx = ti * TILE + copy * WORLD - k * TILE
                val cx = 1300f + dx
                val cy = if (ti == 0) 320f else 380f
                if (cx + bw < 0 || cx - bw > TILE) continue
                c.save()
                c.translate(cx, cy)
                c.rotate(-22f)
                c.drawBitmap(bmp, -bw / 2f, -bh / 2f, p)
                c.restore()
            }
            bmp.recycle()
        }
    }

    /** A rotated box of tiny stars and glowing knots that sits in a galaxy, painted once into a small sprite. */
    private fun makeBox(b: SkyNew.Box, ti: Int, rs: Float): BoxDraw {
        val pad = 8f
        val bw = Math.max(2, Math.ceil(((b.w + 2f * pad) * rs).toDouble()).toInt())
        val bh = Math.max(2, Math.ceil(((b.h + 2f * pad) * rs).toDouble()).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bw / (b.w + 2f * pad), bh / (b.h + 2f * pad))
        c.translate(pad, pad)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (j in b.kind.indices) {
            val s = b.v[j * 3 + 2]
            val mx = b.v[j * 3] + s / 2f
            val my = b.v[j * 3 + 1] + s / 2f
            val col = b.col[j]
            if (b.kind[j] == 0) {
                p.shader = null
                p.color = col
                c.drawCircle(mx, my, s / 2f, p)
            } else {
                val rgb = col and 0x00FFFFFF
                p.shader = RadialGradient(mx, my, s / 2f * 1.4142f * b.stop[j], col, rgb, Shader.TileMode.CLAMP)
                c.drawCircle(mx, my, s / 2f, p)
            }
        }
        return BoxDraw(ti * TILE + b.x + b.w / 2f, b.y + b.h / 2f, b.w / 2f + pad, b.h / 2f + pad, b.rot,
            Math.max(b.w, b.h) / 2f + pad + 4f, bmp)
    }

    private class Acc(val col: Int, val bigDot: Boolean) {
        var n = 0; var sa = 0f; var sr = 0f
        val pts = ArrayList<Float>()
    }

    /** The stars of one depth. The fine field becomes a few batches of dots (one draw call per colour, brightness and size);
     *  the bright ones stay single. Every twelfth fine star is handed to the twinklers instead. */
    private fun buildGroup(g: Int, twOut: ArrayList<FloatArray>): StarGroup {
        val map = HashMap<Long, Acc>()
        val big = ArrayList<BigStar>()
        var count = 0
        for (s in starSpecs) {
            if (s.group != g) continue
            val cr = Color.red(s.col); val cg = Color.green(s.col); val cb = Color.blue(s.col)
            if (g < 4) {
                count++
                if (count % 12 == 0) {   // about one in twelve twinkles on its own
                    twOut.add(floatArrayOf(s.x, s.y, s.r / 0.8f, s.a, cr.toFloat(), cg.toFloat(), cb.toFloat(), g.toFloat()))
                    continue
                }
                val ai = if (s.a < 0.5f) 0 else if (s.a < 0.7f) 1 else 2
                val ri = if (s.r < 0.7f) 0 else 1
                val key = (s.col.toLong() and 0xFFFFFFL) * 8L + ai * 2 + ri
                val acc = map.getOrPut(key) { Acc(s.col, ri == 1) }
                acc.n++; acc.sa += s.a; acc.sr += s.r
                acc.pts.add(s.x); acc.pts.add(s.y)
            } else {
                val halo = if (s.haloR > 0f) Paint(Paint.ANTI_ALIAS_FLAG).also {
                    it.shader = RadialGradient(0f, 0f, s.haloR, Color.argb(Math.round(s.haloA * 255f * 0.7f), cr, cg, cb),
                        Color.argb(0, cr, cg, cb), Shader.TileMode.CLAMP)
                } else null
                val soft = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.argb(Math.round(s.a * 255f * 0.5f * 0.7f), cr, cg, cb) }
                val core = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.argb(Math.round(s.a * 255f * 0.7f), cr, cg, cb) }
                big.add(BigStar(s.x, s.y, s.r, s.haloR, halo, soft, core))
            }
        }
        val buckets = ArrayList<Bucket>()
        for (acc in map.values) {
            val a = acc.sa / acc.n
            val r = acc.sr / acc.n
            val cr = Color.red(acc.col); val cg = Color.green(acc.col); val cb = Color.blue(acc.col)
            // the faint halo is only worth a draw call on the bigger dots
            val soft = if (!acc.bigDot) null else Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND; it.strokeWidth = r * 3.6f
                it.color = Color.argb(Math.round(a * 255f * 0.3f * 0.7f), cr, cg, cb)
            }
            val core = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND; it.strokeWidth = r * 2f
                it.color = Color.argb(Math.round(a * 255f * 0.7f), cr, cg, cb)
            }
            // sorted by x, so a frame can draw just the dots that are on screen
            val cnt = acc.pts.size / 2
            val order = (0 until cnt).sortedBy { acc.pts[it * 2] }
            val pts = FloatArray(cnt * 2)
            val xs = FloatArray(cnt)
            for ((k, i) in order.withIndex()) {
                pts[k * 2] = acc.pts[i * 2]; pts[k * 2 + 1] = acc.pts[i * 2 + 1]; xs[k] = acc.pts[i * 2]
            }
            buckets.add(Bucket(xs, pts, soft, core))
        }
        return StarGroup(buckets.toTypedArray(), big.toTypedArray())
    }

    /** Nebula glows per depth class. Home sets some of them beside their galaxy; the screensaver keeps every one on its galaxy. */
    private fun buildNebs(): Array<Array<NebDraw>> {
        val data = SkyNew.get(context)
        return Array(NEB_GROUPS) { c ->
            val out = ArrayList<NebDraw>()
            for (ti in 0..1) for (n in data[c].tiles[ti].neb) {
                val ox = if (saver) 0f else n.dx
                val oy = if (saver) 0f else n.dy
                val cr = Color.red(n.color); val cg = Color.green(n.color); val cb = Color.blue(n.color)
                val al = Math.min(255, Math.round(n.a * 255f))
                // css: radial-gradient(ellipse at center, color, transparent 66%)
                val sh = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(al, cr, cg, cb), Color.argb(0, cr, cg, cb)),
                    floatArrayOf(0f, 0.66f), Shader.TileMode.CLAMP)
                val m = Matrix()
                m.setScale(n.w / 2f * 1.4142f, n.h / 2f * 1.4142f)
                sh.setLocalMatrix(m)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = sh
                out.add(NebDraw(ti * TILE + n.x + ox + n.w / 2f, n.y + oy + n.h / 2f, n.w, n.h, Math.max(n.w, n.h) / 2f, p))
            }
            out.toTypedArray()
        }
    }

    private fun lowerBound(a: FloatArray, v: Float): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (a[m] < v) lo = m + 1 else hi = m
        }
        return lo
    }

    /** Screen x of an element whose world centre is [b], or NaN when it is off screen. The world wraps every 5200 units. */
    private fun visibleX(b: Float, scroll: Float, ext: Float, viewW: Float): Float {
        var x = (scroll - WORLD / 2f + b) % WORLD
        if (x < 0f) x += WORLD
        if (x - ext < viewW + 40f && x + ext > -40f) return x
        val x2 = x - WORLD
        if (x2 - ext < viewW + 40f && x2 + ext > -40f) return x2
        return Float.NaN
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
        driveUntil = android.os.SystemClock.uptimeMillis() + 200L
        tickTiles()
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
        val gl = gals; val bx = boxes; val sg = groups; val nb = nebs
        if (fb0 == null || fb1 == null || gl == null || bx == null || sg == null || nb == null) return
        val ms = if (t0 == 0L) 0L else (lastFrame - t0)
        val sec = if (motion) ms / 1000f else 0f
        val pos = sec + (if (motion) boost else 0f)   // scroll clock: time plus the focus kick
        val fu = (farOffset + pos * FAR_SPEED) % WORLD

        canvas.save()
        canvas.scale(viewScale, viewScale)
        val viewW = width / viewScale
        // nebulae: three sets (one per depth class), each breathing slowly at its own pace
        val farAlpha = (1f - OVERLAY) * 255f
        for (g in 0 until NEB_GROUPS) {
            val fg = (farOffset + pos * FAR_SPEED * CLASS_SPEEDS[g]) % WORLD
            val s = 0.5f + 0.5f * Math.sin(2.0 * Math.PI * (sec / NEB_PERIOD[g] + NEB_PHASE[g])).toFloat()
            val al = Math.min(255, Math.round(farAlpha * (0.7f + 0.45f * s)))
            for (n in nb[g]) {
                val x = visibleX(n.bx, fg, n.ext, viewW)
                if (x.isNaN()) continue
                n.paint.alpha = al
                canvas.save()
                canvas.translate(x, n.cy)
                canvas.drawRect(-n.w / 2f, -n.h / 2f, n.w / 2f, n.h / 2f, n.paint)
                canvas.restore()
            }
        }
        // milky way: two big soft pictures
        for (k in 0..1) {
            val bmp = if (k == 0) fb0 else fb1
            for (m in -1..1) {
                val x = k * TILE + fu + m * WORLD - WORLD / 2f
                if (x > viewW || x + TILE < 0) continue
                dst.set(x, 0f, x + TILE, DESIGN_H)
                canvas.drawBitmap(bmp, null, dst, farPaint)
            }
        }
        // galaxies: three depth classes, each at its own speed
        for (g in 0 until NEB_GROUPS) {
            val fg = (farOffset + pos * FAR_SPEED * CLASS_SPEEDS[g]) % WORLD
            for (d in gl[g]) {
                val x = visibleX(d.bx, fg, d.ext, viewW)
                if (x.isNaN()) continue
                canvas.save()
                canvas.translate(x, d.cy)
                canvas.rotate(d.rot)
                canvas.drawOval(-d.w / 2f, -d.h / 2f, d.w / 2f, d.h / 2f, d.paint)
                canvas.restore()
            }
            for (d in bx[g]) {
                val x = visibleX(d.bx, fg, d.ext, viewW)
                if (x.isNaN()) continue
                canvas.save()
                canvas.translate(x, d.cy)
                canvas.rotate(d.rot)
                dst.set(-d.hw, -d.hh, d.hw, d.hh)
                canvas.drawBitmap(d.spr, null, dst, farPaint)
                canvas.restore()
            }
        }
        // near stars: steady dots in batches; the twinkling ones are drawn one by one below
        for (g in 0 until STAR_GROUPS) {
            // far-ish stars drift a little slower, close ones a little faster
            val sp = STAR_SPEEDS[g]
            val nu = (nearOffset * sp + pos * NEAR_SPEED * sp) % TILE
            nus[g] = nu
            val grp = sg[g]
            var x = nu - TILE
            while (x < viewW) {
                canvas.save()
                canvas.translate(x, 0f)
                // only the dots that are on screen (the margin covers the parallax shift)
                val lx = -x - 40f
                val hx = viewW - x + 40f
                for (b in grp.buckets) {
                    val lo = lowerBound(b.xs, lx)
                    val hi = lowerBound(b.xs, hx)
                    if (hi <= lo) continue
                    if (b.soft != null) canvas.drawPoints(b.pts, lo * 2, (hi - lo) * 2, b.soft)
                    canvas.drawPoints(b.pts, lo * 2, (hi - lo) * 2, b.core)
                }
                for (st in grp.big) {
                    val px = x + st.x
                    if (px < -24f || px > viewW + 24f) continue
                    canvas.save()
                    canvas.translate(st.x, st.y)
                    if (st.halo != null) canvas.drawCircle(0f, 0f, st.haloR, st.halo)
                    canvas.drawCircle(0f, 0f, st.r, st.soft)
                    canvas.drawCircle(0f, 0f, st.r * 0.55f, st.core)
                    canvas.restore()
                }
                canvas.restore()
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
