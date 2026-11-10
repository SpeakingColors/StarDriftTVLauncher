package dev.tvlauncher.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Stardrift screensaver. The same sky as the home screen (every color spot sits on a galaxy), a 24 hour
 * clock, the date with the day, and a Wi-Fi icon (green online, red offline). The whole clock group drifts
 * very slowly across the screen. The ambient song plays when the Music switch is on. Any button wakes it.
 */
class StardriftDream : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private val anims = ArrayList<ObjectAnimator>()
    private val durations = ArrayList<Long>()
    private var wifi: ImageView? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private fun shadow(t: TextView) = t.setShadowLayer(Ui.px(24f), 0f, Ui.px(2f), 0x99000000.toInt())

    private fun drift(v: View, prop: String, from: Float, to: Float, ms: Long) {
        val a = ObjectAnimator.ofFloat(v, prop, from, to)
        a.duration = ms
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.interpolator = AccelerateDecelerateInterpolator()
        anims.add(a)
        durations.add(ms)
    }

    private fun showNet(ok: Boolean) {
        val w = wifi ?: return
        w.setImageResource(if (ok) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
        w.setColorFilter(if (ok) 0xFF6EE7A8.toInt() else 0xFFFF6B6B.toInt())
        w.contentDescription = if (ok) "Connected" else "No internet"
    }

    private fun netOk(caps: NetworkCapabilities?): Boolean =
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private var root: FrameLayout? = null
    private var built = false
    private var dreaming = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        Ui.init(this)

        val r = FrameLayout(this)
        r.setBackgroundColor(Color.BLACK)
        r.addView(SpaceView(this, true), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = r
        // the clock group is built after the first layout, so it is sized from the real window and not from a guess
        r.addOnLayoutChangeListener { _, l, t, rt, bt, _, _, _, _ ->
            val w = rt - l
            val h = bt - t
            if (!built && w > 0 && h > 0) {
                built = true
                Ui.s = Math.min(w / 1600f, h / 900f)
                Log.i("StardriftDream", "window " + w + "x" + h + " scale " + Ui.s)
                r.post { buildClock(r) }
            }
        }
        setContentView(r)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { showNet(netOk(cm.getNetworkCapabilities(cm.activeNetwork))) } catch (e: Throwable) { showNet(false) }
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { showNet(netOk(caps)) } }
                override fun onLost(network: Network) { main.post { showNet(false) } }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Throwable) { }
    }

    private fun buildClock(r: FrameLayout) {
        try {
            // one column, placed straight on the full-screen layout and moved in both directions; it sits in no smaller box,
            // so nothing can clip it while it drifts (a box around it cut the clock off when it moved down)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            Ui.place(col, 600f, 340f, 400f)

            val clock = TextClock(this).apply {
                format24Hour = "HH:mm"; format12Hour = "HH:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(96f))
                setTextColor(Ui.TEXT)
                includeFontPadding = false
                letterSpacing = -0.01f
                gravity = Gravity.CENTER_HORIZONTAL
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            }
            shadow(clock)
            col.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rowLp.topMargin = Ui.pxi(8f)
            col.addView(row, rowLp)
            val date = TextClock(this).apply {
                format24Hour = "EEEE, MMMM d"; format12Hour = "EEEE, MMMM d"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(24f))
                setTextColor(Ui.TEXT2)
                includeFontPadding = false
            }
            shadow(date)
            row.addView(date)
            val icon = ImageView(this)
            wifi = icon
            val ilp = LinearLayout.LayoutParams(Ui.pxi(26f), Ui.pxi(26f))
            ilp.leftMargin = Ui.pxi(14f)
            row.addView(icon, ilp)
            r.addView(col)

            drift(col, "translationX", -480f * Ui.s, 480f * Ui.s, 300_000L)
            drift(col, "translationY", -240f * Ui.s, 240f * Ui.s, 220_000L)

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try { showNet(netOk(cm.getNetworkCapabilities(cm.activeNetwork))) } catch (e: Throwable) { showNet(false) }
            if (dreaming) startDrift()
            col.post { Log.i("StardriftDream", "clock group at " + col.left + "," + col.top + " size " + col.width + "x" + col.height + " text " + clock.text) }
        } catch (e: Throwable) {
            Log.e("StardriftDream", "clock group failed", e)
        }
    }

    private fun startDrift() {
        // start somewhere along the path, so it is not always the same corner
        for (i in anims.indices) {
            anims[i].start()
            anims[i].currentPlayTime = (Math.random() * durations[i]).toLong()
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        startDrift()
        val prefs = getSharedPreferences("tvlauncher", Context.MODE_PRIVATE)
        HomeMusic.setLevel(prefs.getInt("music_vol", 60) / 100f)
        if (prefs.getBoolean("music", false)) HomeMusic.start(this, "dream")
    }

    override fun onDreamingStopped() {
        dreaming = false
        for (a in anims) a.cancel()
        HomeMusic.stop("dream")
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        for (a in anims) a.cancel()
        anims.clear()
        durations.clear()
        val cb = netCb
        if (cb != null) {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (e: Throwable) { }
            netCb = null
        }
        super.onDetachedFromWindow()
    }
}
