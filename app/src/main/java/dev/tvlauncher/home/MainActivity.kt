package dev.tvlauncher.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/** Home screen. Stage A: sky, greeting, clock, weather with city search, app dock with reorder. */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var repo: AppsRepo
    private lateinit var root: FrameLayout
    private lateinit var space: SpaceView
    private lateinit var content: FrameLayout
    private lateinit var greeting: TextView
    private lateinit var wxIcon: WeatherIconView
    private lateinit var wxTemp: TextView
    private lateinit var wxText: TextView
    private lateinit var wxFeels: TextView
    private lateinit var wxCity: TextView
    private lateinit var dockHolder: LinearLayout
    private lateinit var dockScroll: HorizontalScrollView
    private lateinit var dockLabel: TextView
    private var overlay: FrameLayout? = null
    private var overlayKind = ""
    private var overlayReturn: View? = null
    private lateinit var feedStore: FeedStore
    private val picks = ArrayList<PickCard>()
    private val cwViews = ArrayList<View>()
    private lateinit var cwLabel: TextView
    private var cwRows: List<CwItem>? = null
    private var weather: Weather? = null
    private var reorderPkg: String? = null
    private var quietFocus = false   // no move blip while a popup opens or closes and sets the focus itself
    private var clockView: View? = null

    private val tileU = 140.4f
    private val gapU = 20f

    private val tick = object : Runnable {
        override fun run() {
            greeting.text = greetingFull()
            ui.postDelayed(this, 30_000)
        }
    }
    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, 15 * 60_000L)
        }
    }

    private val feedTick = object : Runnable {
        override fun run() {
            refreshFeeds()
            ui.postDelayed(this, 30 * 60_000L)
        }
    }
    // Right after boot the network or the TV provider may not be ready: feeds and Continue watching try again with growing waits.
    private var feedRetries = 0
    @Volatile private var feedBusy = false
    private val feedRetryRun = Runnable { refreshFeeds() }
    private var contRetries = 0
    private val contRetryRun = Runnable { refreshContinue() }
    private fun backoff(n: Int): Long = Math.min(15_000L shl n, 300_000L)   // 15, 30, 60, 120, 240, 300 s
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    private fun watchNetwork() {
        if (netCb != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    ui.post { refreshAll(false) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Throwable) { }
    }

    private fun unwatchNetwork() {
        try {
            val cb = netCb
            if (cb != null) (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (e: Throwable) { }
        netCb = null
    }

    /** Reloads feeds, New series and Continue watching now, and forgets earlier failures. */
    private fun refreshAll(say: Boolean) {
        if (say) toast("Refreshing...")
        Images.clearFailed()
        feedRetries = 0; contRetries = 0
        refreshFeeds(); refreshContinue()
    }

    // pictures that failed to download (network not up yet after boot) are tried again a few times
    private var cwRetries = 0
    private val cwRetryRun = Runnable { showContinue() }
    private fun cwRetry() {
        if (cwRetries < 7) {
            val wait = Math.min(10_000L shl cwRetries, 60_000L)   // 10, 20, 40, 60 s ...
            cwRetries++
            ui.removeCallbacks(cwRetryRun); ui.postDelayed(cwRetryRun, wait)
        }
    }
    private val cwTick = object : Runnable {
        override fun run() {
            refreshContinue()
            ui.postDelayed(this, 5 * 60_000L)
        }
    }
    private val cycleTick = object : Runnable {
        override fun run() {
            picks.forEach { it.next() }
            warmImages()
            ui.postDelayed(this, 6_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Images.init(this)
        prefs = getSharedPreferences("tvlauncher", Context.MODE_PRIVATE)
        repo = AppsRepo(this, prefs)
        IconStyle.play = prefs.getString("play_style", "colors") ?: "colors"
        Sfx.init(this)
        Sfx.load(prefs)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        space = SpaceView(this)
        root.addView(space, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.clipChildren = false
        content = FrameLayout(this)
        content.clipChildren = false
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window.decorView.isSoundEffectsEnabled = false
        root.isSoundEffectsEnabled = false
        space.setMotion(prefs.getBoolean("motion", true))
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
            if (now != null && now.width > 0) {
                val loc = IntArray(2)
                now.getLocationInWindow(loc)
                val cx = (loc[0] + now.width / 2f) / Ui.s
                space.setParallax(800f - cx)
                if (!quietFocus && old != null && old !== now && old.isAttachedToWindow && old.width > 0 && reorderPkg == null) {
                    val ol = IntArray(2)
                    old.getLocationInWindow(ol)
                    val dx = Math.abs((loc[0] + now.width / 2f) - (ol[0] + old.width / 2f))
                    val dy = Math.abs((loc[1] + now.height / 2f) - (ol[1] + old.height / 2f))
                    Sfx.play(if (dx >= dy) Sfx.MOVE_H else Sfx.MOVE_V)
                }
            }
        }

        feedStore = FeedStore(prefs, applicationContext)
        buildHeader()
        buildDock()
        buildPicks()
        buildContinue()
        applyRows()
    }

    // ---------- header: greeting, clock, date, weather, top buttons ----------

    private fun text(size: Float, color: Int, thin: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size))
        setTextColor(color)
        includeFontPadding = false
        if (thin) typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }

    private fun buildHeader() {
        // greeting, clock and date are one focus block: press OK on it to set your name
        val clockBox = GlowFrame(this)
        clockBox.radiusU = 14f
        clockBox.clipChildren = false
        Ui.focusable(clockBox, 1.04f)
        clockBox.contentDescription = "Greeting, clock and date, press to set your name"
        clockBox.setOnClickListener { openNamePopup() }
        content.addView(clockBox)
        Ui.place(clockBox, 72f, 16f, 272f, 160f)
        clockView = clockBox

        greeting = text(24f, Ui.TEXT2)
        clockBox.addView(greeting)
        Ui.place(greeting, 16f, 12f)

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(78f))
            setTextColor(Ui.TEXT)
            includeFontPadding = false
            letterSpacing = -0.025f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
        clockBox.addView(clock)
        Ui.place(clock, 16f, 40f, 210f, 77f)

        val date = TextClock(this).apply {
            format24Hour = "EEEE, MMMM d"; format12Hour = "EEEE, MMMM d"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(22f))
            setTextColor(Ui.TEXT2)
            includeFontPadding = false
        }
        clockBox.addView(date)
        Ui.place(date, 16f, 120f)

        val divider = View(this)
        divider.setBackgroundColor(0x33FFFFFF)
        content.addView(divider)
        Ui.place(divider, 352f, 40f, 1f, 112f)

        // weather block: focusable, press OK to choose the city
        val box = GlowFrame(this)
        box.clipChildren = false
        Ui.focusable(box, 1.04f)
        box.setOnClickListener { chooseCity() }
        content.addView(box)
        Ui.place(box, 368f, 28f, 560f, 152f)
        wxIcon = WeatherIconView(this)
        box.addView(wxIcon)
        Ui.place(wxIcon, 14f, 28f, 96f, 96f)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        wxTemp = text(44f, Ui.TEXT, true)
        wxText = text(20f, Ui.TEXT2)
        wxFeels = text(17f, Ui.TEXT2)
        wxCity = text(17f, Ui.TEXT3)
        col.addView(wxTemp); col.addView(wxText); col.addView(wxFeels); col.addView(wxCity)
        for (i in 1 until col.childCount) (col.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.pxi(3f)
        box.addView(col)
        Ui.place(col, 124f, 10f)

        // top right buttons
        val icons = listOf(
            IconSpec(0, "Play Store") { openPackage("com.android.vending") },
            IconSpec(1, "Inputs") { openInputsPane() },
            IconSpec(2, "Network") { openNetworkPane() },
            IconSpec(4, "Sound") { openSoundPane() },
            IconSpec(3, "Settings") { openSettingsPane() }
        )
        var x = 1512f - icons.size * 60f - (icons.size - 1) * 14f
        for (spec in icons) {
            val b = IconButton(this, spec.kind)
            b.contentDescription = spec.label
            b.setOnClickListener { spec.action() }
            content.addView(b)
            Ui.place(b, x, 28f, 60f, 60f)
            x += 74f
        }
    }

    /** Greeting with the name set in Settings (empty by default). */
    private fun greetingFull(): String {
        val n = (prefs.getString("name", "") ?: "").trim()
        return greetingText() + (if (n.isEmpty()) "" else ", $n")
    }

    private fun greetingText(): String {
        val c = Calendar.getInstance()
        val hf = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        val h = c.get(Calendar.HOUR_OF_DAY)
        val sunset = weather?.sunset ?: 18.0
        val eve = Math.min(18.0, sunset)
        return when {
            h in 5..11 -> "Good morning"
            hf >= 12 && hf < eve -> "Good afternoon"
            hf >= eve && h < 22 -> "Good evening"
            else -> "Good night"
        }
    }

    // ---------- weather ----------

    private fun cityFromPrefs(): Triple<String, Double, Double>? {
        val name = prefs.getString("city_name", null) ?: return null
        val lat = prefs.getString("city_lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("city_lon", null)?.toDoubleOrNull() ?: return null
        return Triple(name, lat, lon)
    }

    private fun chooseCity() {
        showCitySearch(this, { r -> ui.post(r) }) { c ->
            prefs.edit().putString("city_name", c.name).putString("city_lat", c.lat.toString())
                .putString("city_lon", c.lon.toString()).remove("wx_json").apply()
            weather = null
            refreshWeather()
        }
    }

    private fun showWeather(w: Weather?, city: String?) {
        weather = w
        if (city == null) {
            wxIcon.visibility = View.INVISIBLE
            wxTemp.text = ""
            wxText.text = "Choose your city"
            wxFeels.text = "Press OK here"
            wxCity.text = ""
            return
        }
        if (w == null) {
            wxIcon.visibility = View.INVISIBLE
            wxTemp.text = ""
            wxText.text = "Weather unavailable"
            wxFeels.text = "Check the internet connection"
            wxCity.text = city
            return
        }
        wxIcon.visibility = View.VISIBLE
        wxIcon.set(w.kind(), !w.isDay, w.toHorizon())
        wxTemp.text = Math.round(w.tempC).toString() + "°C"
        wxText.text = w.text()
        wxFeels.text = "Feels like " + Math.round(w.feelsC) + "°C"
        wxCity.text = city
        greeting.text = greetingFull()
    }

    private fun refreshWeather() {
        val city = cityFromPrefs()
        if (city == null) { showWeather(null, null); return }
        val cached = prefs.getString("wx_json", null)
        if (weather == null && cached != null) {
            try { showWeather(WeatherRepo.parse(cached), city.first) } catch (e: Throwable) { }
        }
        Thread {
            try {
                val json = WeatherRepo.fetchJson(city.second, city.third)
                val w = WeatherRepo.parse(json)
                prefs.edit().putString("wx_json", json).apply()
                ui.post { showWeather(w, city.first) }
            } catch (e: Throwable) {
                ui.post { if (weather == null) showWeather(null, city.first) }
            }
        }.start()
    }

    // ---------- dock ----------

    private fun buildDock() {
        dockLabel = text(20f, Ui.TEXT2)
        dockLabel.text = "Apps"
        content.addView(dockLabel)
        Ui.place(dockLabel, 88f, HomeRows.y(prefs, HomeRows.APPS))

        dockScroll = HorizontalScrollView(this)
        dockScroll.isHorizontalScrollBarEnabled = false
        dockScroll.clipToPadding = false
        dockScroll.clipChildren = false
        dockScroll.overScrollMode = View.OVER_SCROLL_NEVER
        dockScroll.setPadding(Ui.pxi(40f), Ui.pxi(40f), Ui.pxi(40f), Ui.pxi(40f))
        dockHolder = LinearLayout(this)
        dockHolder.orientation = LinearLayout.HORIZONTAL
        dockHolder.clipChildren = false
        dockScroll.addView(dockHolder)
        content.addView(dockScroll)
        Ui.place(dockScroll, 48f, HomeRows.y(prefs, HomeRows.APPS) - 2f, 1504f, tileU + 80f)
    }

    private fun tileParams(last: Boolean, label: Boolean = true): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(Ui.pxi(tileU), Ui.pxi(if (label) tileU + 10f + 26f else tileU))
        lp.rightMargin = if (last) 0 else Ui.pxi(gapU)
        return lp
    }

    private fun rebuildDock(focusPkg: String?) {
        dockHolder.removeAllViews()
        val apps = repo.pinned()
        var focusView: View? = null
        for (a in apps) {
            val t = AppTile(this, a, tileU, false)
            t.moving = a.pkg == reorderPkg
            t.setOnClickListener { if (reorderPkg == null) launch(a) else endReorder() }
            t.setOnLongClickListener { if (reorderPkg == null) dockMenu(a); true }
            dockHolder.addView(t, tileParams(false, false))
            if (a.pkg == focusPkg) focusView = t
        }
        val all = AppTile(this, null, tileU, false)
        all.setOnClickListener { if (reorderPkg == null) openAllApps() }
        dockHolder.addView(all, tileParams(true, false))
        val fv = focusView ?: if (focusPkg == null) null else dockHolder.getChildAt(0)
        // While moving, focus stays parked on the dock scroller, so rebuilding the tiles
        // never hands focus to the weather. The moving tile is drawn as selected by itself.
        if (reorderPkg != null) fv?.post { scrollDockTo(fv) } else fv?.post { fv.requestFocus() }
    }

    private fun scrollDockTo(v: View) {
        val pad = Ui.pxi(40f)
        val l = dockHolder.left + v.left - dockScroll.scrollX
        val r = dockHolder.left + v.right - dockScroll.scrollX
        val w = dockScroll.width
        if (l < pad) dockScroll.smoothScrollBy(l - pad, 0)
        else if (r > w - pad) dockScroll.smoothScrollBy(r - (w - pad), 0)
    }

    private fun dockMenu(a: AppEntry) {
        showMenu(this, a.label, listOf<Pair<String, () -> Unit>>(
            "Move" to { startReorder(a.pkg) },
            "App info" to { appInfo(a.pkg) },
            "Remove from dock" to { togglePin(a.pkg) },
            "Hide" to { hideApp(a.pkg) },
            "Uninstall" to { uninstall(a.pkg) }
        ), playToggle(a, 2))
    }

    private fun nextPlayStyle(): String = when (IconStyle.play) { "colors" -> "black"; "black" -> "white"; else -> "colors" }

    /** Only the Play Store tile has a look choice: Colors, Black or White. The menu stays open so it can be cycled quickly. */
    private fun playToggle(a: AppEntry, at: Int): MenuToggle? {
        if (a.pkg != "com.android.vending") return lookToggle(a, at)
        return MenuToggle({ "Icon look: " + nextPlayStyle().replaceFirstChar { it.uppercase() } }, at) {
            val next = nextPlayStyle()
            IconStyle.play = next
            prefs.edit().putString("play_style", next).apply()
            refreshTiles(window.decorView)
        }
    }

    private fun nextLook(l: String): String = when (l) { "original" -> "black"; "black" -> "white"; else -> "original" }

    /** Every other app has a look too: Original, Black (logo on black) or White (logo in its own colour on a light tile). */
    private fun lookToggle(a: AppEntry, at: Int): MenuToggle =
        MenuToggle({ "Icon look: " + nextLook(a.look).replaceFirstChar { it.uppercase() } }, at) {
            val next = nextLook(a.look)
            a.look = next
            prefs.edit().putString("look_" + a.pkg, next).apply()
            refreshTiles(window.decorView)
        }

    /** The Play Store look is read when a tile draws, so redrawing is enough: no rebuild, focus stays where it is. */
    private fun refreshTiles(v: View) {
        if (v is AppTile) v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) refreshTiles(v.getChildAt(i))
    }

    private fun hideApp(pkg: String) {
        repo.setHidden(pkg, true)
        rebuildDock(null)
        if (overlayKind == "apps") { closeOverlay(false); openAllApps() }
        toast("Hidden. Show it again in Settings, Hidden apps")
    }

    /** Puts the three rows where the saved order and switches say, and hides the ones that are off. */
    private fun applyRows() {
        val vis = HomeRows.visible(prefs)
        val appsOn = HomeRows.APPS in vis
        dockLabel.visibility = if (appsOn) View.VISIBLE else View.GONE
        dockScroll.visibility = if (appsOn) View.VISIBLE else View.GONE
        if (appsOn) {
            val y = HomeRows.y(prefs, HomeRows.APPS)
            Ui.place(dockLabel, 88f, y)
            Ui.place(dockScroll, 48f, y - 2f, 1504f, tileU + 80f)
        }
        val contOn = HomeRows.CONTINUE in vis
        cwLabel.visibility = if (contOn) View.VISIBLE else View.GONE
        if (contOn) Ui.place(cwLabel, 88f, HomeRows.y(prefs, HomeRows.CONTINUE))
        buildPicks()
        showContinue()
    }

    /** Settings, Home rows: show or hide each row and choose the order. This is also where a hidden row comes back. */
    private fun openHomeRows() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val order = HomeRows.order(prefs)
        var first: View? = null
        for ((i, id) in order.withIndex()) {
            val on = HomeRows.isOn(prefs, id)
            val row = focusableRow()
            row.setPadding(Ui.pxi(26f), 0, Ui.pxi(26f), 0)
            val t = text(24f, if (on) Ui.TEXT else Ui.TEXT3)
            t.text = HomeRows.name(id)
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val s = text(20f, if (on) Ui.ACCENT else Ui.TEXT3)
            s.text = if (on) "Shown" else "Hidden"
            row.addView(s)
            row.setOnClickListener {
                val items = ArrayList<Pair<String, () -> Unit>>()
                items.add((if (on) "Hide this row" else "Show this row") to {
                    HomeRows.setOn(prefs, id, !on); applyRows(); closeOverlay(false); openHomeRows()
                })
                if (i > 0) items.add("Move up" to { HomeRows.move(prefs, id, -1); applyRows(); closeOverlay(false); openHomeRows() })
                if (i < order.size - 1) items.add("Move down" to { HomeRows.move(prefs, id, 1); applyRows(); closeOverlay(false); openHomeRows() })
                showMenu(this, HomeRows.name(id), items)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(84f))
            lp.bottomMargin = Ui.pxi(12f)
            col.addView(row, lp)
            if (first == null) first = row
        }
        val hint = text(18f, Ui.TEXT3)
        hint.text = "Rows are listed top to bottom. A hidden row comes back here."
        col.addView(hint)
        showOverlay("rows", "Home rows", scrollBody(col), 790f, first)
    }

    private fun openHiddenApps() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        var first: View? = null
        for (a in repo.hiddenApps) {
            val row = focusableRow()
            row.setPadding(Ui.pxi(26f), 0, Ui.pxi(26f), 0)
            val t = text(24f, Ui.TEXT)
            t.text = a.label
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val s = text(20f, Ui.ACCENT)
            s.text = "Show"
            row.addView(s)
            row.setOnClickListener { repo.setHidden(a.pkg, false); rebuildDock(null); closeOverlay(false); openHiddenApps() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(84f))
            lp.bottomMargin = Ui.pxi(12f)
            col.addView(row, lp)
            if (first == null) first = row
        }
        if (first == null) {
            val e = text(22f, Ui.TEXT3)
            e.text = "No hidden apps"
            col.addView(e)
        }
        showOverlay("hidden", "Hidden apps", scrollBody(col), 790f, first)
    }

    private fun startReorder(pkg: String) {
        reorderPkg = pkg
        toast("Use left and right, then press OK")
        // Park focus on the scroller first: it is not removed when the tiles are rebuilt.
        dockScroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        dockScroll.isFocusable = true
        dockScroll.requestFocus()
        rebuildDock(pkg)
    }

    private fun endReorder() {
        val p = reorderPkg
        reorderPkg = null
        dockScroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rebuildDock(p)
    }

    private fun moveInDock(delta: Int) {
        val pkg = reorderPkg ?: return
        val list = repo.pinnedPackages()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        list[i] = list[j].also { list[j] = list[i] }
        repo.savePinned(list)
        rebuildDock(pkg)
    }

    private fun togglePin(pkg: String) {
        val list = repo.pinnedPackages()
        if (pkg in list) list.remove(pkg) else list.add(pkg)
        repo.savePinned(list)
        rebuildDock(null)
        if (overlayKind == "apps") { closeOverlay(false); openAllApps() }
    }

    // ---------- overlay screens ----------

    private fun showOverlay(kind: String, title: String, body: View, bodyH: Float = 790f, first: View? = null) {
        if (overlay != null) closeOverlay(false)
        overlayReturn = currentFocus
        val layer = FrameLayout(this)
        layer.setBackgroundColor(0xE6000000.toInt())
        layer.isClickable = true
        val head = text(32f, Ui.TEXT)
        head.text = title
        layer.addView(head)
        Ui.place(head, 88f, 28f)
        layer.addView(body)
        Ui.place(body, 60f, 74f, 1500f, bodyH + 16f)
        root.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay = layer
        overlayKind = kind
        Sfx.play(Sfx.OPEN)
        quietFocus = true
        first?.requestFocus()
        quietFocus = false
    }

    private fun closeOverlay(restoreFocus: Boolean = true) {
        val l = overlay ?: return
        val wasSources = overlayKind == "sources" || overlayKind == "newseries"
        if (overlayKind == "network") speedCancel?.set(true)
        if (overlayKind == "name") {
            try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0) } catch (e: Throwable) { }
        }
        if (overlayKind == "phone") {
            phone?.stop()
            phone = null
            if (phoneAdded > 0) { phoneAdded = 0; buildPicks(); refreshFeeds() }
        }
        root.removeView(l)
        overlay = null
        overlayKind = ""
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (wasSources && sourcesChanged && restoreFocus) { sourcesChanged = false; buildPicks(); refreshFeeds() }
        if (restoreFocus) {
            Sfx.play(Sfx.CLOSE)
            quietFocus = true
            val r = overlayReturn
            if (r != null && r.isAttachedToWindow && r.isShown) r.requestFocus()
            else if (dockScroll.visibility == View.VISIBLE) dockHolder.getChildAt(dockHolder.childCount - 1)?.requestFocus()
            else clockView?.requestFocus()
            quietFocus = false
        }
    }

    private fun scrollBody(col: LinearLayout): ScrollView {
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.clipChildren = false
        col.clipChildren = false
        scroll.setPadding(Ui.pxi(28f), Ui.pxi(28f), Ui.pxi(28f), Ui.pxi(28f))
        scroll.addView(col)
        return scroll
    }

    // ---------- popups (flush right under the top icons, or next to the clock) ----------

    private fun showPane(kind: String, title: String, col: LinearLayout, first: View?) =
        showPopup(kind, title, col, first, 1140f, 98f, 460f)

    private fun showPopup(kind: String, title: String, col: LinearLayout, first: View?, xu: Float, yu: Float, wu: Float) {
        if (overlay != null) closeOverlay(false)
        overlayReturn = currentFocus
        val layer = FrameLayout(this)
        layer.isClickable = true
        layer.setOnClickListener { closeOverlay() }
        val panel = PopupPanel(this, Ui.pxi(780f))
        panel.orientation = LinearLayout.VERTICAL
        panel.clipChildren = false
        panel.isClickable = true
        panel.background = Ui.rounded(0xF70A081A.toInt(), 22f, 0x38FFFFFF, 1f)
        panel.elevation = Ui.px(16f)
        panel.setPadding(Ui.pxi(2f), Ui.pxi(14f), Ui.pxi(2f), Ui.pxi(2f))
        val head = text(16f, 0xFFB4BCCB.toInt())
        head.text = title
        head.setPadding(Ui.pxi(20f), 0, Ui.pxi(20f), 0)
        panel.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.clipChildren = false
        col.clipChildren = false
        scroll.setPadding(Ui.pxi(12f), Ui.pxi(8f), Ui.pxi(12f), Ui.pxi(12f))
        scroll.addView(col)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layer.addView(panel)
        Ui.place(panel, xu, yu, wu)
        root.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay = layer
        overlayKind = kind
        Sfx.play(Sfx.OPEN)
        quietFocus = true
        first?.requestFocus()
        quietFocus = false
    }

    /** Small rounded square with a line icon, like the icons in the mockup rows. */
    private fun iconBox(res: Int, sizeU: Float, radiusU: Float, bg: Int, glyphU: Float): View {
        val box = FrameLayout(this)
        box.background = Ui.rounded(bg, radiusU)
        val iv = ImageView(this)
        iv.setImageResource(res)
        iv.setColorFilter(Ui.ACCENT)
        box.addView(iv, FrameLayout.LayoutParams(Ui.pxi(glyphU), Ui.pxi(glyphU), android.view.Gravity.CENTER))
        box.layoutParams = LinearLayout.LayoutParams(Ui.pxi(sizeU), Ui.pxi(sizeU))
        return box
    }

    private fun paneLabel(col: LinearLayout, s: String) {
        val l = text(14f, 0xFFB4BCCB.toInt())
        l.text = s.uppercase()
        l.letterSpacing = 0.1f
        col.addView(l)
        (l.layoutParams as LinearLayout.LayoutParams).apply { topMargin = Ui.pxi(10f); bottomMargin = Ui.pxi(8f); leftMargin = Ui.pxi(8f) }
    }

    private fun paneRow(col: LinearLayout, name: String, sub: String?, accent: Boolean, icon: Int = 0, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val row = focusableRow(accent)
        row.setPadding(Ui.pxi(if (icon != 0) 14f else 20f), 0, Ui.pxi(20f), 0)
        if (icon != 0) {
            val big = accent && sub == null
            val box = if (big) iconBox(icon, 42f, 13f, 0x3DB69CFF, 24f) else iconBox(icon, 36f, 11f, 0x1AFFFFFF, 22f)
            row.addView(box)
            (box.layoutParams as LinearLayout.LayoutParams).rightMargin = Ui.pxi(16f)
        }
        val tc = LinearLayout(this)
        tc.orientation = LinearLayout.VERTICAL
        val n = text(if (accent && sub == null) 21f else 20f, if (accent) Ui.ACCENT else Ui.TEXT)
        n.text = name
        tc.addView(n)
        if (sub != null) {
            val s = text(15f, 0xFFB4BCCB.toInt())
            s.text = sub
            tc.addView(s)
            (s.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.pxi(2f)
        }
        row.addView(tc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pill = text(16f, Ui.TEXT)
        pill.gravity = android.view.Gravity.CENTER
        pill.visibility = View.GONE
        row.addView(pill, LinearLayout.LayoutParams(Ui.pxi(76f), Ui.pxi(36f)))
        row.setOnClickListener { onClick() }
        val h = if (sub != null) 72f else if (accent && icon != 0) 68f else if (icon != 0) 56f else 52f
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(h))
        lp.bottomMargin = Ui.pxi(8f)
        col.addView(row, lp)
        return row to pill
    }

    private fun openSettingsPane() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val (all, _) = paneRow(col, "All TV settings", null, true, R.drawable.ic_tune) { closeOverlay(false); startSafe(Intent(Settings.ACTION_SETTINGS)) }

        paneLabel(col, "Quick actions")
        val (closeTile, cacheTile) = quickPair(col,
            "Close background apps", "Best effort", R.drawable.ic_close_apps, { closeBackgroundApps() },
            "Clear cache", "Asks Android to clear it", R.drawable.ic_trash, { clearCache() })
        // Down from "All TV settings" lands on Close background apps (the one directly underneath), not on Clear cache.
        if (all.id == View.NO_ID) all.id = View.generateViewId()
        all.nextFocusDownId = closeTile.id
        closeTile.nextFocusUpId = all.id
        cacheTile.nextFocusUpId = all.id

        paneLabel(col, "Shortcuts")
        val shortcuts = listOf(
            Triple("Apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, R.drawable.ic_apps),
            Triple("Display", Settings.ACTION_DISPLAY_SETTINGS, R.drawable.ic_display),
            Triple("Sound", Settings.ACTION_SOUND_SETTINGS, R.drawable.ic_sound),
            Triple("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, R.drawable.ic_storage)
        )
        for ((i, sc) in shortcuts.withIndex()) {
            val (name, action, icon) = sc
            // some TVs have no separate Display screen: it lives on the Display & Sound page
            val fallback = if (action == Settings.ACTION_DISPLAY_SETTINGS) Intent(Settings.ACTION_SOUND_SETTINGS) else null
            val (srow, _) = paneRow(col, name, null, false, icon) { closeOverlay(false); startSafe(Intent(action), fallback) }
            if (i == 0) srow.nextFocusUpId = closeTile.id
        }

        paneLabel(col, "Launcher")
        paneRow(col, "Home rows", null, false, R.drawable.ic_apps) { closeOverlay(false); openHomeRows() }
        paneRow(col, "Hidden apps", null, false, R.drawable.ic_hidden) { closeOverlay(false); openHiddenApps() }
        showPane("settings", "Settings", col, all)
    }

    // ---------- sound popup (speaker icon): music and effects, each with on/off and a volume ----------

    private fun soundToggle(col: LinearLayout, label: String, icon: Int, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val row = focusableRow()
        row.tag = "silent"
        row.setPadding(Ui.pxi(14f), 0, Ui.pxi(14f), 0)
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(Ui.ACCENT)
        row.addView(iv, LinearLayout.LayoutParams(Ui.pxi(24f), Ui.pxi(24f)))
        val t = text(20f, Ui.TEXT)
        t.text = label
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = Ui.pxi(12f) })
        val pill = text(16f, Ui.TEXT)
        pill.gravity = android.view.Gravity.CENTER
        row.addView(pill, LinearLayout.LayoutParams(Ui.pxi(70f), Ui.pxi(32f)))
        fun paint() {
            val on = get()
            pill.text = if (on) "On" else "Off"
            pill.setTextColor(if (on) 0xFF0D1117.toInt() else Ui.TEXT)
            pill.background = Ui.rounded(if (on) Ui.ACCENT else 0xFF343A4A.toInt(), 16f)
        }
        paint()
        row.setOnClickListener { set(!get()); paint() }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(54f)).also { it.bottomMargin = Ui.pxi(6f) })
        return row
    }

    /** Volume bar: Left and Right change it in steps of 10. */
    private fun soundSlider(col: LinearLayout, get: () -> Int, set: (Int) -> Unit): View {
        val row = focusableRow()
        row.tag = "silent"
        row.setPadding(Ui.pxi(14f), 0, Ui.pxi(14f), 0)
        val bar = LinearLayout(this)
        val fill = View(this)
        fill.background = Ui.rounded(Ui.ACCENT, 5f)
        val rest = View(this)
        rest.background = Ui.rounded(0xFF343A4A.toInt(), 5f)
        bar.addView(fill, LinearLayout.LayoutParams(0, Ui.pxi(10f), 1f))
        bar.addView(rest, LinearLayout.LayoutParams(0, Ui.pxi(10f), 1f))
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pct = text(18f, Ui.TEXT2)
        pct.gravity = android.view.Gravity.END
        row.addView(pct, LinearLayout.LayoutParams(Ui.pxi(58f), ViewGroup.LayoutParams.WRAP_CONTENT))
        fun paint() {
            val v = get()
            pct.text = "$v%"
            fill.layoutParams = LinearLayout.LayoutParams(0, Ui.pxi(10f), Math.max(v, 1).toFloat())
            rest.layoutParams = LinearLayout.LayoutParams(0, Ui.pxi(10f), Math.max(100 - v, 1).toFloat())
            bar.requestLayout()
        }
        paint()
        row.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val old = get()
                val v = (old + (if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10 else -10)).coerceIn(0, 100)
                if (v != old) { set(v); paint(); Sfx.play(Sfx.MOVE_H) } else if (ev.repeatCount == 0) Sfx.play(Sfx.BUMP)
                true
            } else false
        }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(44f)).also { it.bottomMargin = Ui.pxi(6f) })
        return row
    }

    private fun openSoundPane() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val music = soundToggle(col, "Music", R.drawable.ic_music, { prefs.getBoolean("music", false) }) { on ->
            prefs.edit().putBoolean("music", on).apply()
            if (on) HomeMusic.start(this) else HomeMusic.stop()
            Sfx.play(if (on) Sfx.ON else Sfx.OFF)
        }
        soundSlider(col, { prefs.getInt("music_vol", 60) }) { v ->
            prefs.edit().putInt("music_vol", v).apply()
            HomeMusic.setLevel(v / 100f)
        }
        val fx = soundToggle(col, "Effects", R.drawable.ic_sparkle, { prefs.getBoolean("fx", true) }) { on ->
            if (on) { prefs.edit().putBoolean("fx", true).apply(); Sfx.load(prefs); Sfx.play(Sfx.ON) }
            else { Sfx.play(Sfx.OFF); prefs.edit().putBoolean("fx", false).apply(); Sfx.load(prefs) }
        }
        (fx.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.pxi(4f)
        soundSlider(col, { prefs.getInt("fx_vol", 45) }) { v ->
            prefs.edit().putInt("fx_vol", v).apply()
            Sfx.load(prefs)
        }
        val hint = text(14f, 0xFF8F98AA.toInt())
        hint.text = "OK turns on or off. Left and Right change the volume."
        hint.setPadding(Ui.pxi(10f), Ui.pxi(4f), Ui.pxi(10f), Ui.pxi(2f))
        col.addView(hint)
        showPane("sound", "Sound", col, music)
    }

    // ---------- name popup (press OK on the clock) ----------

    private fun popupButton(label: String, primary: Boolean, onClick: () -> Unit): View {
        val b = GlowRow(this)
        b.radiusU = 14f
        b.gravity = android.view.Gravity.CENTER
        b.isFocusable = true
        b.isClickable = true
        b.clipChildren = false
        val t = text(20f, if (primary) 0xFF0D1117.toInt() else Ui.TEXT)
        t.text = label
        if (primary) t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.addView(t)
        fun paint(f: Boolean) {
            b.background = if (primary) Ui.rounded(if (f) 0xFFCDB9FF.toInt() else Ui.ACCENT, 14f)
                else Ui.rounded(if (f) 0xE61C1840.toInt() else 0x1AFFFFFF, 14f, if (f) 0 else 0x2EFFFFFF, if (f) 0f else 1f)
        }
        paint(false)
        b.setOnFocusChangeListener { v, f -> v.translationZ = if (f) 8f else 0f; paint(f); v.invalidate() }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun openNamePopup() {
        val now = (prefs.getString("name", "") ?: "").trim()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val input = EditText(this)
        input.hint = "Type a name"
        input.setHintTextColor(Ui.TEXT3)
        input.setTextColor(Ui.TEXT)
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(24f))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setSingleLine(true)
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(24))
        input.background = Ui.rounded(0x14FFFFFF, 14f, 0x40FFFFFF, 1f)
        input.setPadding(Ui.pxi(16f), 0, Ui.pxi(16f), 0)
        if (now.isNotEmpty()) { input.setText(now); input.setSelection(now.length) }
        col.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(52f)).also { it.bottomMargin = Ui.pxi(10f) })
        fun save() {
            prefs.edit().putString("name", input.text.toString().trim()).apply()
            greeting.text = greetingFull()
            closeOverlay()
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.clipChildren = false
        val saveB = popupButton("Save", true) { save() }
        val cancelB = popupButton("Cancel", false) { closeOverlay() }
        line.addView(saveB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also { it.rightMargin = Ui.pxi(5f) })
        line.addView(cancelB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also { it.leftMargin = Ui.pxi(5f) })
        col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(50f)))
        showPopup("name", "Your name (empty hides it)", col, input, 84f, 170f, 400f)
    }

    private fun infoRow(col: LinearLayout, k: String, v: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(Ui.pxi(20f), 0, Ui.pxi(20f), 0)
        row.background = Ui.rounded(Ui.CARD, 16f, Ui.STROKE, 1f)
        val kt = text(20f, 0xFFB4BCCB.toInt())
        kt.text = k
        val vt = text(20f, Ui.TEXT)
        vt.text = v
        vt.gravity = android.view.Gravity.END
        row.addView(kt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(vt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(60f))
        lp.bottomMargin = Ui.pxi(10f)
        col.addView(row, lp)
    }

    @Suppress("DEPRECATION")
    private fun openNetworkPane() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val note = text(16f, 0xFFB4BCCB.toInt())
        note.text = "Read from the TV when you open this. The network name is never shown."
        col.addView(note)
        (note.layoutParams as LinearLayout.LayoutParams).bottomMargin = Ui.pxi(14f)
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            val lp = if (net != null) cm.getLinkProperties(net) else null
            val wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val type = when {
                caps == null -> "Not connected"
                wifi -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
            infoRow(col, "Connection", type)
            if (caps != null) {
                val ok = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                infoRow(col, "Internet", if (ok) "Connected" else "No internet")
            }
            val ip = lp?.linkAddresses?.map { it.address }?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            if (ip != null) infoRow(col, "IP address", ip)
            val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
            if (gw != null) infoRow(col, "Gateway", gw)
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(", ")
            if (!dns.isNullOrEmpty()) infoRow(col, "DNS", dns)
            if (wifi) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val wi = wm.connectionInfo
                if (wi != null) {
                    val rssi = wi.rssi
                    if (rssi > -127 && rssi < 0) {
                        val q = when { rssi >= -55 -> "Excellent"; rssi >= -67 -> "Good"; rssi >= -80 -> "Fair"; else -> "Weak" }
                        infoRow(col, "Signal", q + " (" + rssi + " dBm)")
                    }
                    if (wi.linkSpeed > 0) infoRow(col, "Link speed", wi.linkSpeed.toString() + " Mbps")
                }
            }
        } catch (e: Throwable) {
            infoRow(col, "Status", "Cannot read")
        }
        val speedMsg = text(20f, Ui.TEXT)
        val (speedBtn, _) = paneRow(col, "Run speed test", "Download, about 12 seconds", true) { startSpeedTest(speedMsg) }
        col.addView(speedMsg)
        (speedMsg.layoutParams as LinearLayout.LayoutParams).apply { topMargin = Ui.pxi(4f); bottomMargin = Ui.pxi(14f) }
        paneRow(col, "Network settings", null, false) { closeOverlay(false); startSafe(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        showPane("network", "Network", col, speedBtn)
    }

    private var speedCancel: java.util.concurrent.atomic.AtomicBoolean? = null
    private var speedRunning = false

    private fun startSpeedTest(msg: TextView) {
        if (speedRunning) return
        speedRunning = true
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        speedCancel = cancel
        msg.text = "Checking " + SpeedTest.HOST + "..."
        Thread {
            val lat = SpeedTest.latencyMs()
            if (lat == null) {
                ui.post {
                    speedRunning = false
                    msg.text = "Cannot reach " + SpeedTest.HOST + ". Check the internet connection."
                }
                return@Thread
            }
            val res = try {
                SpeedTest.download(12, cancel) { mbps, left ->
                    ui.post { if (!cancel.get()) msg.text = "Testing... " + Math.round(mbps) + " Mbps (" + left + " s left)" }
                }
            } catch (e: Throwable) { null }
            ui.post {
                speedRunning = false
                if (cancel.get()) return@post
                msg.text = if (res == null) "The test failed. Try again."
                    else "Download: " + Math.round(res) + " Mbps\nLatency: " + lat + " ms"
            }
        }.start()
    }

    private fun openInputsPane() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        var first: View? = null
        var list: List<android.media.tv.TvInputInfo> = emptyList()
        var tm: android.media.tv.TvInputManager? = null
        try {
            tm = getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager
            list = (tm?.tvInputList ?: emptyList()).filter { it.isPassthroughInput }
        } catch (e: Throwable) {
        }
        if (list.isEmpty()) {
            val n = text(18f, 0xFFB4BCCB.toInt())
            n.text = "This TV does not share its inputs with apps. Use the INPUT button on the remote."
            col.addView(n)
        }
        val named = list.map { info ->
            val label = try { (info.loadCustomLabel(this) ?: info.loadLabel(this)).toString() } catch (e: Throwable) { info.id }
            info to label
        }.sortedBy { it.second.lowercase() }
        for ((info, label) in named) {
            val connected = try { tm?.getInputState(info.id) == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED } catch (e: Throwable) { false }
            val row = focusableRow()
            row.setPadding(Ui.pxi(16f), 0, Ui.pxi(22f), 0)
            val ibox = iconBox(R.drawable.ic_hdmi, 44f, 13f, 0x1AFFFFFF, 26f)
            row.addView(ibox)
            (ibox.layoutParams as LinearLayout.LayoutParams).rightMargin = Ui.pxi(16f)
            val tc = LinearLayout(this)
            tc.orientation = LinearLayout.VERTICAL
            val n = text(22f, Ui.TEXT)
            n.text = label
            val s = text(15f, 0xFFB4BCCB.toInt())
            s.text = if (connected) "Connected" else "No signal"
            tc.addView(n); tc.addView(s)
            (s.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.pxi(2f)
            row.addView(tc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val dot = View(this)
            dot.background = Ui.rounded(if (connected) 0xFF5EEAD4.toInt() else 0xFF6B7280.toInt(), 6f)
            row.addView(dot, LinearLayout.LayoutParams(Ui.pxi(12f), Ui.pxi(12f)))
            val id = info.id
            row.setOnClickListener {
                closeOverlay(false)
                startSafe(Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(76f))
            lp.bottomMargin = Ui.pxi(10f)
            col.addView(row, lp)
            if (first == null) first = row
        }
        if (first == null) {
            val b = focusableRow()
            b.setPadding(Ui.pxi(20f), 0, Ui.pxi(20f), 0)
            val bt = text(21f, Ui.ACCENT)
            bt.text = "Close"
            b.addView(bt)
            b.setOnClickListener { closeOverlay() }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(60f)))
            first = b
        }
        showPane("inputs", "Inputs", col, first)
    }

    private fun freeBytes(): Long = try { android.os.StatFs(dataDir.path).availableBytes } catch (e: Throwable) { -1L }

    /**
     * No app can clear other apps' cache directly (that permission is system only). The documented way is
     * StorageManager.allocateBytes: ask for nearly all the space Android is willing to give us, which makes the
     * system delete cached files to make room. The reserved file is deleted right away.
     */
    private fun clearCache() {
        toast("Clearing cache...")
        Thread {
            val msg = try {
                val sm = getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                val uuid = sm.getUuidForPath(filesDir)
                val before = freeBytes()
                val allocatable = sm.getAllocatableBytes(uuid)
                // allocatable = free space plus the cache Android is willing to delete
                val clearableMb = (allocatable - before) / (1024 * 1024)
                val want = allocatable - 64L * 1024 * 1024
                if (want <= 0L) "Nothing to clear: Android reports no cache" else {
                    val f = java.io.File(filesDir, "space.tmp")
                    try {
                        java.io.FileOutputStream(f).use { sm.allocateBytes(it.fd, want) }
                    } finally { f.delete() }
                    val mb = (freeBytes() - before) / (1024 * 1024)
                    if (mb >= 1) "Cache cleared: $mb MB freed"
                    else if (clearableMb >= 5) "Android reports $clearableMb MB of cache but nothing was freed"
                    else "Nothing to clear: Android reports no cache"
                }
            } catch (e: Throwable) {
                "Android would not clear the cache (" + e.javaClass.simpleName + ")"
            }
            ui.post { toast(msg) }
        }.start()
    }

    private fun closeBackgroundApps() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val before = mi.availMem
            for (a in repo.all) {
                if (a.pkg != packageName) am.killBackgroundProcesses(a.pkg)
            }
            ui.postDelayed({
                val m2 = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(m2)
                val freed = (m2.availMem - before) / (1024 * 1024)
                val free = m2.availMem / (1024 * 1024)
                toast(if (freed >= 5) "Closed background apps: $freed MB freed, $free MB free now"
                    else "Nothing much was running in the background, $free MB free")
            }, 1500L)
        } catch (e: Throwable) {
            toast("Could not close background apps on this TV")
        }
    }

    private fun openAllApps() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        var row: LinearLayout? = null
        var first: View? = null
        repo.all.forEachIndexed { i, a ->
            if (i % 9 == 0) {
                row = LinearLayout(this)
                row!!.clipChildren = false
                row!!.orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = Ui.pxi(18f)
                col.addView(row, lp)
            }
            val t = AppTile(this, a, tileU)
            t.setOnClickListener { closeOverlay(false); launch(a) }
            t.setOnLongClickListener {
                val isPinned = a.pkg in repo.pinnedPackages()
                showMenu(this, a.label, listOf<Pair<String, () -> Unit>>(
                    "App info" to { appInfo(a.pkg) },
                    (if (isPinned) "Remove from dock" else "Add to dock") to { togglePin(a.pkg) },
                    "Hide" to { hideApp(a.pkg) },
                    "Uninstall" to { uninstall(a.pkg) }
                ), playToggle(a, 1))
                true
            }
            row!!.addView(t, tileParams(i % 9 == 8))
            if (first == null) first = t
        }
        if (first == null) toast("No apps found")
        showOverlay("apps", "All apps", scrollBody(col), 800f, first)
    }

    // ---------- picks row ----------

    private val pickViews = ArrayList<View>()
    private var shownCats: List<String> = emptyList()

    /** Categories with a source switched on. The Crunchyroll card also needs the app to share something. */
    private fun visibleCats(): List<FeedCategory> = Feeds.categories.filter { cat ->
        Feeds.allSources(prefs).any { it.category == cat.id && Feeds.isOn(prefs, it) } &&
            (cat.id != "crapp" || !(feedStore.byCategory["crapp"] ?: emptyList()).isEmpty())
    }

    private fun buildPicks() {
        for (v in pickViews) content.removeView(v)
        pickViews.clear()
        picks.clear()
        val on = visibleCats()
        shownCats = on.map { it.id }
        if (HomeRows.PICKS !in HomeRows.visible(prefs)) return
        val py = HomeRows.y(prefs, HomeRows.PICKS)
        if (on.isEmpty()) {
            val hint = text(20f, Ui.TEXT2)
            hint.text = "No feed sources are on. Press OK here to choose some."
            hint.setPadding(Ui.pxi(24f), 0, Ui.pxi(24f), 0)
            hint.gravity = android.view.Gravity.CENTER_VERTICAL
            hint.background = Ui.rounded(Ui.CARD, 20f, Ui.STROKE, 1f)
            Ui.focusable(hint, 1.03f)
            hint.setOnClickListener { openSources() }
            content.addView(hint)
            Ui.place(hint, 88f, py, 1424f, 156f)
            pickViews.add(hint)
            return
        }
        val n = on.size
        val cardW = (1424f - (n - 1) * 20f) / n
        on.forEachIndexed { i, cat ->
            val c = PickCard(this, cat)
            c.startOffset = i
            c.setOnClickListener { openCategory(cat) }
            c.setOnLongClickListener {
                if (cat.id == "crapp") showMenu(this, "New series", listOf("Refresh" to { refreshAll(true) }, "Choose apps" to { openNewSeriesApps() }))
                else showMenu(this, "Picks", listOf("Refresh" to { refreshAll(true) }, "Edit sources" to { openSources() }))
                true
            }
            content.addView(c)
            Ui.place(c, 88f + i * (cardW + 20f), py, cardW, 156f)
            picks.add(c)
            pickViews.add(c)
        }
        updatePicks()
    }

    private fun updatePicks() {
        for (c in picks) {
            val items = feedStore.byCategory[c.category.id] ?: emptyList()
            val msg = if (!feedStore.loaded) "Loading..." else "Nothing to show yet (check the internet connection)"
            c.setItems(items, msg)
        }
        warmImages()
    }

    private fun refreshFeeds() {
        if (feedBusy) return
        feedBusy = true
        Thread {
            try { feedStore.refresh() } catch (e: Throwable) { }
            feedBusy = false
            ui.post {
                if (visibleCats().map { it.id } != shownCats) buildPicks() else updatePicks()
                val seriesOn = Feeds.isOn(prefs, Feeds.builtins.first { it.id == "crapp" }) && NewSeries.chosen(prefs).isNotEmpty()
                val missing = feedStore.errors.isNotEmpty() ||
                    Feeds.categories.any { c ->
                        val on = if (c.id == "crapp") seriesOn else true
                        on && (feedStore.byCategory[c.id] ?: emptyList()).isEmpty()
                    }
                ui.removeCallbacks(feedRetryRun)
                if (missing && feedRetries < 8) { ui.postDelayed(feedRetryRun, backoff(feedRetries)); feedRetries++ }
                else if (!missing) feedRetries = 0
            }
        }.start()
    }

    private fun warmImages() {
        for (c in picks) {
            val list = feedStore.byCategory[c.category.id] ?: continue
            if (list.isEmpty()) continue
            val cur = c.current ?: continue
            val i = list.indexOf(cur)
            ensureImage(cur) { c.imageChanged() }
            ensureImage(list[(i + 1) % list.size]) { }
        }
    }

    private fun ensureImage(item: FeedItem, done: () -> Unit) {
        val u = item.imageUrl
        if (u != null) {
            if (Images.peek(u) == null) Images.load(u, 480) { done() } else done()
            return
        }
        if (item.imageResolved) return
        item.imageResolved = true
        Thread {
            val og = Feeds.ogImage(item.link)
            if (og != null) { item.imageUrl = og; ui.post { ensureImage(item, done) } }
            else ui.postDelayed({ item.imageResolved = false }, 30_000L)
        }.start()
    }

    /** News items open in the reader popup; items that carry an app link (New series) open that app. */
    private fun openItem(item: FeedItem) {
        if (item.link.startsWith("intent:") || item.category == "crapp") { openLink(item.link); return }
        val meta = item.source + (if (item.time > 0) ", " + Feeds.ago(item.time) else "")
        showReader(this, item.title, meta, item.body, item.link)
    }

    private fun openLink(link: String) {
        if (link.isEmpty()) { toast("Nothing to open"); return }
        if (link.startsWith("intent:")) {
            try {
                startSafe(Intent.parseUri(link, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Throwable) { toast("Cannot open that") }
            return
        }
        startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** [accent] gives the purple tinted look of the main buttons (All TV settings, Run speed test). */
    private fun focusableRow(accent: Boolean = false): LinearLayout {
        val row = GlowRow(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.clipChildren = false
        fun idle() = if (accent) Ui.rounded(0x24B69CFF, 18f, 0x73B69CFF, 1f) else Ui.rounded(Ui.CARD, 18f, Ui.STROKE, 1f)
        row.background = idle()
        row.setOnFocusChangeListener { v, f ->
            v.translationZ = if (f) 8f else 0f
            v.background = if (f) Ui.rounded(0xE61C1840.toInt(), 18f) else idle()
            v.invalidate()
        }
        return row
    }

    /** Two small actions side by side, like the mockup: icon on top, name and a short line at the bottom. */
    private fun quickPair(col: LinearLayout, aName: String, aSub: String, aIcon: Int, aClick: () -> Unit,
                          bName: String, bSub: String, bIcon: Int, bClick: () -> Unit): Pair<View, View> {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.clipChildren = false
        lateinit var left: View
        lateinit var right: View
        fun tile(name: String, sub: String, icon: Int, click: () -> Unit, first: Boolean) {
            val t = focusableRow()
            t.orientation = LinearLayout.VERTICAL
            t.gravity = android.view.Gravity.START
            t.setPadding(Ui.pxi(16f), Ui.pxi(14f), Ui.pxi(16f), Ui.pxi(14f))
            t.addView(iconBox(icon, 40f, 12f, 0x1AFFFFFF, 24f))
            val gap = View(this)
            t.addView(gap, LinearLayout.LayoutParams(1, 0, 1f))
            val n = text(18f, Ui.TEXT)
            n.text = name
            t.addView(n)
            val s = text(14f, 0xFFB4BCCB.toInt())
            s.text = sub
            t.addView(s)
            (s.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.pxi(1f)
            t.setOnClickListener { click() }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (first) lp.rightMargin = Ui.pxi(14f)
            line.addView(t, lp)
            t.id = View.generateViewId()
            if (first) left = t else right = t
        }
        tile(aName, aSub, aIcon, aClick, true)
        tile(bName, bSub, bIcon, bClick, false)
        col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(128f)).also { it.bottomMargin = Ui.pxi(8f) })
        return left to right
    }

    private fun openCategory(cat: FeedCategory) {
        val items = feedStore.byCategory[cat.id] ?: emptyList()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        var first: View? = null
        if (items.isEmpty()) {
            val t = text(22f, Ui.TEXT2)
            t.text = "Nothing to show yet. Check the internet connection."
            col.addView(t)
        }
        for (it in items) {
            val row = focusableRow()
            row.setPadding(Ui.pxi(14f), Ui.pxi(12f), Ui.pxi(24f), Ui.pxi(12f))
            val iv = android.widget.ImageView(this)
            iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            iv.background = Ui.rounded(Ui.mix(cat.color, 0xFF1A1440.toInt(), 0.65f), 12f)
            iv.clipToOutline = true
            iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, Ui.px(12f))
                }
            }
            row.addView(iv, LinearLayout.LayoutParams(Ui.pxi(180f), Ui.pxi(100f)))
            val tc = LinearLayout(this)
            tc.orientation = LinearLayout.VERTICAL
            tc.setPadding(Ui.pxi(20f), 0, 0, 0)
            val ti = text(24f, Ui.TEXT)
            ti.text = it.title
            ti.maxLines = 2
            ti.ellipsize = android.text.TextUtils.TruncateAt.END
            val me = text(17f, 0xFFB4BCCB.toInt())
            me.text = it.source + (if (it.time > 0) ", " + Feeds.ago(it.time) else "")
            tc.addView(ti); tc.addView(me)
            (me.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.pxi(6f)
            row.addView(tc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val entry = it
            row.setOnClickListener { openItem(entry) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = Ui.pxi(12f)
            col.addView(row, lp)
            if (first == null) first = row
            val item = it
            ensureImage(item) {
                val b = Images.peek(item.imageUrl)
                if (b != null) iv.setImageBitmap(b)
            }
        }
        showOverlay("category", cat.tag, scrollBody(col), 790f, first)
    }

    private fun openSources() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val note = text(20f, Ui.TEXT2)
        note.text = "Only what you pick shows on Home. No suggestions, no sponsored items."
        col.addView(note)
        (note.layoutParams as LinearLayout.LayoutParams).bottomMargin = Ui.pxi(16f)
        var first: View? = null
        for (s in Feeds.allSources(prefs)) {
            val row = focusableRow()
            row.setPadding(Ui.pxi(26f), Ui.pxi(14f), Ui.pxi(26f), Ui.pxi(14f))
            val tc = LinearLayout(this)
            tc.orientation = LinearLayout.VERTICAL
            val n = text(24f, Ui.TEXT)
            n.text = s.name
            val d = text(16f, 0xFFB4BCCB.toInt())
            d.text = if (s.url.startsWith("tv:")) "Pick the apps. Hidden when none share anything." else Feeds.category(s.category).tag + ", " + s.url
            d.maxLines = 1
            d.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            tc.addView(n); tc.addView(d)
            row.addView(tc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val pill = text(18f, Ui.TEXT)
            pill.gravity = android.view.Gravity.CENTER
            fun paint() {
                val on = if (s.url.startsWith("tv:")) NewSeries.chosen(prefs).isNotEmpty() else Feeds.isOn(prefs, s)
                pill.text = if (on) "On" else "Off"
                pill.setTextColor(if (on) 0xFF0D1117.toInt() else Ui.TEXT)
                pill.background = Ui.rounded(if (on) Ui.ACCENT else 0xFF343A4A.toInt(), 20f)
            }
            paint()
            row.addView(pill, LinearLayout.LayoutParams(Ui.pxi(90f), Ui.pxi(40f)))
            if (s.url.startsWith("tv:")) {
                row.setOnClickListener { closeOverlay(false); openNewSeriesApps() }
            } else {
                row.setOnClickListener { Feeds.setOn(prefs, s, !Feeds.isOn(prefs, s)); paint(); sourcesChanged = true }
            }
            if (!s.builtin) {
                row.isLongClickable = true
                row.setOnLongClickListener {
                    showMenu(this, s.name, listOf("Remove this feed" to {
                        Feeds.removeCustom(prefs, s.url); sourcesChanged = true; closeOverlay(false); openSources()
                    }))
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(84f))
            lp.bottomMargin = Ui.pxi(12f)
            col.addView(row, lp)
            if (first == null) first = row
        }
        val ph = focusableRow()
        ph.setPadding(Ui.pxi(26f), 0, Ui.pxi(26f), 0)
        val pt = text(24f, Ui.ACCENT)
        pt.text = "Add feeds from your phone"
        ph.addView(pt)
        ph.setOnClickListener { closeOverlay(false); openPhoneAdd() }
        val phLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(84f))
        phLp.bottomMargin = Ui.pxi(12f)
        col.addView(ph, phLp)
        val add = focusableRow()
        add.setPadding(Ui.pxi(26f), 0, Ui.pxi(26f), 0)
        val at = text(24f, Ui.ACCENT)
        at.text = "Add a feed by web address"
        add.addView(at)
        add.setOnClickListener { addFeedFlow() }
        col.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(84f)))
        sourcesChanged = false
        showOverlay("sources", "Feed sources", scrollBody(col), 790f, first)
    }

    private var sourcesChanged = false
    private var phone: PhoneAdd? = null
    private var phoneAdded = 0

    private fun openPhoneAdd() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val p = PhoneAdd(prefs) { n -> ui.post { phoneAdded = n } }
        val ok = p.start()
        val ip = if (ok) p.address() else null
        fun line(s: String, size: Float, color: Int, top: Float) {
            val t = text(size, color)
            t.text = s
            col.addView(t)
            (t.layoutParams as LinearLayout.LayoutParams).topMargin = Ui.pxi(top)
        }
        if (!ok || ip == null) {
            line("Cannot start. Is the TV connected to your home network?", 20f, Ui.TEXT, 0f)
        } else {
            phone = p
            phoneAdded = 0
            line("On your phone, on the same Wi-Fi, open:", 18f, 0xFFB4BCCB.toInt(), 0f)
            line("http://" + ip + ":" + p.port, 26f, Ui.ACCENT, 8f)
            line("PIN", 18f, 0xFFB4BCCB.toInt(), 24f)
            line(p.pin, 56f, Ui.TEXT, 4f)
            line("Paste one or more feed addresses, pick Anime or Games, press Add. This closes when you leave this screen.", 17f, 0xFFB4BCCB.toInt(), 24f)
        }
        val b = focusableRow()
        b.setPadding(Ui.pxi(20f), 0, Ui.pxi(20f), 0)
        val bt = text(21f, Ui.ACCENT)
        bt.text = "Done"
        b.addView(bt)
        b.setOnClickListener { closeOverlay(false); openSources() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(60f))
        lp.topMargin = Ui.pxi(28f)
        col.addView(b, lp)
        showPane("phone", "Add from phone", col, b)
    }

    private fun addFeedFlow() {
        showTextInput(this, "Feed address. YouTube: https://www.youtube.com/feeds/videos.xml?channel_id=...", "https://") { url ->
            if (!url.startsWith("http")) { toast("The address must start with http"); return@showTextInput }
            showMenu(this, "Show it under", Feeds.categories.filter { it.id != "crapp" }.map { cat ->
                cat.tag to { validateAndAdd(cat.id, url) }
            })
        }
    }

    private fun validateAndAdd(category: String, url: String) {
        toast("Checking the feed...")
        Thread {
            val name = try { android.net.Uri.parse(url).host ?: url } catch (e: Throwable) { url }
            val ok = try { Feeds.fetch(FeedSource("tmp", category, name, url, false)).isNotEmpty() } catch (e: Throwable) { false }
            ui.post {
                if (ok) {
                    Feeds.addCustom(prefs, category, name, url)
                    sourcesChanged = true
                    toast("Feed added")
                    if (overlayKind == "sources") { closeOverlay(false); openSources() }
                } else toast("That address does not look like a working feed")
            }
        }.start()
    }

    // ---------- continue watching ----------

    private fun buildContinue() {
        cwLabel = text(20f, Ui.TEXT2)
        cwLabel.text = "Continue watching"
        content.addView(cwLabel)
        Ui.place(cwLabel, 88f, HomeRows.y(prefs, HomeRows.CONTINUE))
        if (checkSelfPermission(WatchNext.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_listings", false)) {
            prefs.edit().putBoolean("asked_listings", true).apply()
            try { requestPermissions(arrayOf(WatchNext.PERMISSION), 7) } catch (e: Throwable) { }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshContinue()
    }

    private fun refreshContinue() {
        Thread {
            val rows = WatchNext.read(this)
            ui.post {
                cwRows = rows; showContinue()
                ui.removeCallbacks(contRetryRun)
                val granted = checkSelfPermission(WatchNext.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (rows.isNullOrEmpty()) {
                    if (granted && contRetries < 6) { ui.postDelayed(contRetryRun, backoff(contRetries)); contRetries++ }
                } else contRetries = 0
            }
        }.start()
    }

    private fun labelFor(pkg: String): String =
        CwApps.known[pkg] ?: repo.all.firstOrNull { it.pkg == pkg }?.label ?: pkg

    private fun showContinue() {
        for (v in cwViews) content.removeView(v)
        cwViews.clear()
        if (HomeRows.CONTINUE !in HomeRows.visible(prefs)) return
        val cy = HomeRows.y(prefs, HomeRows.CONTINUE) + 36f
        val rows = (cwRows ?: emptyList()).filter { CwApps.isOn(prefs, it.pkg) }
        val cards = ArrayList<View>()
        if (rows.isNotEmpty()) {
            cwLabel.text = "Continue watching"
            for (r in rows.take(6)) {
                val card = ContinueCard(this, labelFor(r.pkg), CwApps.color(r.pkg), r.title, r.left(), r.progress())
                card.setOnClickListener { openCw(r) }
                val iu = r.imageUri
                if (iu != null) Images.load(iu, 640) { b -> if (b != null) card.image = b else cwRetry() }
                cards.add(card)
            }
        } else {
            // Google TV gave nothing for the chosen apps: show what was opened from here
            val recent = Recents.get(prefs).filter { p -> CwApps.isOn(prefs, p.first) }
            val byPkg = repo.all.associateBy { it.pkg }
            val shown = recent.filter { byPkg.containsKey(it.first) }.take(6)
            cwLabel.text = if (shown.isEmpty()) "Continue watching" else "Recently opened"
            for ((pkg, time) in shown) {
                val app = byPkg[pkg]!!
                val card = ContinueCard(this, app.label, CwApps.color(pkg), app.label, "Opened " + Feeds.ago(time), 0f)
                card.setOnClickListener { launch(app) }
                cards.add(card)
            }
            if (shown.isEmpty()) {
                val msg = text(20f, 0xFFB4BCCB.toInt())
                val denied = checkSelfPermission(WatchNext.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED
                msg.text = if (denied) "Allow TV listings access so Continue watching can show. Hold a card to choose apps."
                    else "Nothing from your apps yet. Hold here to choose apps."
                val box = focusableRow()
                box.setPadding(Ui.pxi(24f), 0, Ui.pxi(24f), 0)
                box.addView(msg)
                box.isLongClickable = true
                box.setOnLongClickListener {
                    showMenu(this, "Continue watching", listOf("Refresh" to { refreshAll(true) }, "Choose apps" to { openChooseApps() }))
                    true
                }
                box.setOnClickListener {
                    if (denied) { try { requestPermissions(arrayOf(WatchNext.PERMISSION), 7) } catch (e: Throwable) { } }
                    else openChooseApps()
                }
                content.addView(box)
                Ui.place(box, 88f, cy, 1424f, 176f)
                cwViews.add(box)
            }
        }
        val n = 6
        val w = (1424f - (n - 1) * 20f) / n
        cards.forEachIndexed { i, card ->
            card.setOnLongClickListener {
                showMenu(this, "Continue watching", listOf("Refresh" to { refreshAll(true) }, "Choose apps" to { openChooseApps() }))
                true
            }
            content.addView(card)
            Ui.place(card, 88f + i * (w + 20f), cy, w, 176f)
            cwViews.add(card)
        }
    }

    private fun openCw(r: CwItem) {
        Recents.add(prefs, r.pkg)
        val uri = r.intentUri
        if (uri != null) {
            try {
                val i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (e: Throwable) { }
        }
        val app = repo.all.firstOrNull { it.pkg == r.pkg }
        if (app != null) launch(app) else toast("Cannot open that")
    }

    private fun openChooseApps() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val note = text(20f, Ui.TEXT2)
        note.text = "Only the apps you switch on show in the row."
        col.addView(note)
        (note.layoutParams as LinearLayout.LayoutParams).bottomMargin = Ui.pxi(16f)
        val pkgs = LinkedHashSet<String>()
        for (k in CwApps.known.keys) if (repo.all.any { it.pkg == k }) pkgs.add(k)
        for (r in cwRows ?: emptyList()) if (!CwApps.isBlocked(r.pkg)) pkgs.add(r.pkg)
        var first: View? = null
        for (pkg in pkgs) {
            val row = focusableRow()
            row.setPadding(Ui.pxi(26f), 0, Ui.pxi(26f), 0)
            val n = text(24f, Ui.TEXT)
            n.text = labelFor(pkg)
            row.addView(n, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val pill = text(18f, Ui.TEXT)
            pill.gravity = android.view.Gravity.CENTER
            fun paint() {
                val on = CwApps.isOn(prefs, pkg)
                pill.text = if (on) "On" else "Off"
                pill.setTextColor(if (on) 0xFF0D1117.toInt() else Ui.TEXT)
                pill.background = Ui.rounded(if (on) Ui.ACCENT else 0xFF343A4A.toInt(), 20f)
            }
            paint()
            row.addView(pill, LinearLayout.LayoutParams(Ui.pxi(90f), Ui.pxi(40f)))
            row.setOnClickListener { CwApps.setOn(prefs, pkg, !CwApps.isOn(prefs, pkg)); paint(); showContinue() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(72f))
            lp.bottomMargin = Ui.pxi(12f)
            col.addView(row, lp)
            if (first == null) first = row
        }
        if (first == null) {
            val t = text(22f, Ui.TEXT2)
            t.text = "None of your streaming apps were found."
            col.addView(t)
        }
        showOverlay("chooser", "Continue watching apps", scrollBody(col), 790f, first)
    }

    /** Which apps fill the New series card. All off hides the card and the other cards take the space. */
    private fun openNewSeriesApps() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val note = text(20f, Ui.TEXT2)
        note.text = "New shows from the apps you switch on. All off hides the card. Not every app shares its list with the TV."
        col.addView(note)
        (note.layoutParams as LinearLayout.LayoutParams).bottomMargin = Ui.pxi(16f)
        var first: View? = null
        for (pkg in NewSeries.apps) {
            if (repo.all.none { it.pkg == pkg }) continue
            val row = focusableRow()
            row.setPadding(Ui.pxi(26f), 0, Ui.pxi(26f), 0)
            val n = text(24f, Ui.TEXT)
            n.text = labelFor(pkg)
            row.addView(n, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val pill = text(18f, Ui.TEXT)
            pill.gravity = android.view.Gravity.CENTER
            fun paint() {
                val on = NewSeries.isOn(prefs, pkg)
                pill.text = if (on) "On" else "Off"
                pill.setTextColor(if (on) 0xFF0D1117.toInt() else Ui.TEXT)
                pill.background = Ui.rounded(if (on) Ui.ACCENT else 0xFF343A4A.toInt(), 20f)
            }
            paint()
            row.addView(pill, LinearLayout.LayoutParams(Ui.pxi(90f), Ui.pxi(40f)))
            row.setOnClickListener { NewSeries.setOn(prefs, pkg, !NewSeries.isOn(prefs, pkg)); paint(); sourcesChanged = true }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(72f))
            lp.bottomMargin = Ui.pxi(12f)
            col.addView(row, lp)
            if (first == null) first = row
        }
        if (first == null) {
            val t = text(22f, Ui.TEXT2)
            t.text = "None of the streaming apps were found."
            col.addView(t)
        }
        sourcesChanged = false
        showOverlay("newseries", "New series apps", scrollBody(col), 790f, first)
    }

    // ---------- actions ----------

    private fun launch(a: AppEntry) {
        Recents.add(prefs, a.pkg)
        val i = Intent(Intent.ACTION_MAIN).setComponent(a.component)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startSafe(i)
    }

    private fun openPackage(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) toast("Not installed") else startSafe(i)
    }

    private fun appInfo(pkg: String) =
        startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    private fun uninstall(pkg: String) =
        startSafe(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

    private fun startSafe(i: Intent, fallback: Intent? = null) {
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            if (fallback != null) startSafe(fallback) else toast("Cannot open that on this TV")
        } catch (e: Throwable) {
            toast("Cannot open that")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- lifecycle and keys ----------

    override fun onResume() {
        super.onResume()
        repo.load()
        rebuildDock((currentFocus as? AppTile)?.app?.pkg ?: "first")
        ui.removeCallbacks(tick); ui.post(tick)
        ui.removeCallbacks(weatherTick); ui.post(weatherTick)
        ui.removeCallbacks(feedTick); ui.post(feedTick)
        cwRetries = 0; feedRetries = 0; contRetries = 0
        watchNetwork()
        ui.removeCallbacks(cwTick); ui.post(cwTick)
        ui.removeCallbacks(cycleTick); ui.postDelayed(cycleTick, 6_000L)
        space.start()
        HomeMusic.setLevel(prefs.getInt("music_vol", 60) / 100f)
        Sfx.load(prefs)
        if (prefs.getBoolean("music", false)) HomeMusic.start(this)
    }

    override fun onPause() {
        HomeMusic.stop()
        speedCancel?.set(true)
        phone?.stop()
        phone = null
        ui.removeCallbacks(tick)
        ui.removeCallbacks(weatherTick)
        ui.removeCallbacks(feedTick)
        ui.removeCallbacks(cwTick)
        ui.removeCallbacks(cwRetryRun)
        ui.removeCallbacks(feedRetryRun)
        ui.removeCallbacks(contRetryRun)
        unwatchNetwork()
        ui.removeCallbacks(cycleTick)
        space.stop()
        super.onPause()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (reorderPkg != null && e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveInDock(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveInDock(1); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { endReorder(); return true }
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (f != null && f !is EditText) {
                        val dir = when (e.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                            else -> View.FOCUS_DOWN
                        }
                        val edge = f.focusSearch(dir) == null
                        val handled = super.dispatchKeyEvent(e)
                        // nothing took the key and there is nothing to move to: soft bump at the edge
                        if (!handled && edge && e.repeatCount == 0) Sfx.play(Sfx.BUMP)
                        return handled
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (e.repeatCount == 0 && f != null && f !is EditText && f.tag != "silent") Sfx.play(Sfx.SELECT)
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // A home screen must not exit on Back. Back closes the apps list.
    @Deprecated("Home screen stays open")
    override fun onBackPressed() {
        if (overlay != null) closeOverlay()
    }
}

private class IconSpec(val kind: Int, val label: String, val action: () -> Unit)

/** Round top button with a simple line icon on a 24 grid. */
private class IconButton(context: Context, private val kind: Int) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Ui.TEXT
    }
    private val r = RectF()

    private val vec: android.graphics.drawable.Drawable? =
        if (kind == 4) context.getDrawable(R.drawable.ic_sound)?.mutate() else null

    init { Ui.focusable(this, 1.06f) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        bg.color = Ui.CARD
        c.drawCircle(w / 2, w / 2, w / 2 - 1f, bg)
        if (isFocused) {
            Ui.drawFocus(c, 0f, 0f, w, w, w / 2)
        } else {
            ring.color = Ui.STROKE
            ring.strokeWidth = Ui.px(1.2f)
            c.drawCircle(w / 2, w / 2, w / 2 - ring.strokeWidth / 2, ring)
        }
        if (kind == 4) {
            val d = vec
            if (d != null) {
                val sz = Math.round(Ui.px(28f))
                val o = Math.round((w - sz) / 2f)
                d.setBounds(o, o, o + sz, o + sz)
                d.setTint(Ui.TEXT)
                d.draw(c)
            }
            return
        }
        val k = Ui.px(28f) / 24f
        c.save()
        c.translate((w - Ui.px(28f)) / 2, (w - Ui.px(28f)) / 2)
        c.scale(k, k)
        p.strokeWidth = 1.8f
        when (kind) {
            0 -> {
                c.drawRoundRect(RectF(5f, 8f, 19f, 20f), 2f, 2f, p)
                r.set(9f, 3.5f, 15f, 10f); c.drawArc(r, 180f, 180f, false, p)
                c.drawLine(10.5f, 11.5f, 10.5f, 16.5f, p); c.drawLine(10.5f, 11.5f, 15f, 14f, p); c.drawLine(10.5f, 16.5f, 15f, 14f, p)
            }
            1 -> {
                c.drawRoundRect(RectF(3f, 5f, 21f, 17f), 2f, 2f, p)
                c.drawLine(9f, 20.5f, 15f, 20.5f, p)
                c.drawLine(8f, 11f, 16f, 11f, p); c.drawLine(13f, 8f, 16f, 11f, p); c.drawLine(13f, 14f, 16f, 11f, p)
            }
            2 -> {
                for (rad in floatArrayOf(4f, 8f, 12f)) {
                    r.set(12f - rad, 19f - rad, 12f + rad, 19f + rad)
                    c.drawArc(r, 225f, 90f, false, p)
                }
                val f = Paint(p); f.style = Paint.Style.FILL
                c.drawCircle(12f, 19f, 1.3f, f)
            }
            else -> {
                c.drawLine(4f, 8f, 20f, 8f, p); c.drawLine(4f, 16f, 20f, 16f, p)
                val f = Paint(p); f.style = Paint.Style.FILL; f.color = Ui.CARD
                c.drawCircle(9f, 8f, 2.4f, f); c.drawCircle(9f, 8f, 2.4f, p)
                c.drawCircle(15f, 16f, 2.4f, f); c.drawCircle(15f, 16f, 2.4f, p)
            }
        }
        c.restore()
    }
}

/** Popup panel that grows with its content up to a maximum height. */
private class PopupPanel(context: Context, private val maxH: Int) : LinearLayout(context) {
    override fun onMeasure(w: Int, h: Int) {
        val cap = Math.min(maxH, View.MeasureSpec.getSize(h))
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}
