package dev.tvlauncher.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private fun focusRow(context: Context, label: String, sub: String? = null, onClick: () -> Unit): View {
    val row = GlowRow(context)
    row.radiusU = 14f
    row.clipChildren = false
    row.orientation = LinearLayout.VERTICAL
    row.setPadding(Ui.pxi(22f), Ui.pxi(12f), Ui.pxi(22f), Ui.pxi(12f))
    val t = TextView(context)
    t.text = label; t.setTextColor(Ui.TEXT); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(22f))
    row.addView(t)
    if (sub != null) {
        val s = TextView(context)
        s.text = sub; s.setTextColor(Ui.TEXT3); s.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(16f))
        row.addView(s)
    }
    row.isFocusable = true
    row.isClickable = true
    row.background = Ui.rounded(0x00000000, 14f)
    row.setOnFocusChangeListener { v, f ->
        v.translationZ = if (f) 8f else 0f
        v.background = Ui.rounded(if (f) 0x40B69CFF else 0x00000000, 14f)
        v.invalidate()
    }
    row.setOnClickListener { Sfx.play(Sfx.SELECT); onClick() }
    return row
}

private fun panelDialog(context: Context, widthU: Float, content: View): Dialog {
    val d = Dialog(context)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.window?.setDimAmount(0.6f)
    d.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    val frame = LinearLayout(context)
    frame.orientation = LinearLayout.VERTICAL
    frame.clipChildren = false
    frame.clipToPadding = false   // the panel padding is smaller than the glow, so it must not clip to it
    frame.background = Ui.rounded(0xF0100E22.toInt(), 22f, Ui.STROKE, 1f)
    frame.setPadding(Ui.pxi(18f), Ui.pxi(18f), Ui.pxi(18f), Ui.pxi(18f))
    frame.addView(content)
    // transparent margin around the panel so the focus glow of the rows is not cut by the window
    val outer = FrameLayout(context)
    outer.clipChildren = false
    outer.clipToPadding = false
    outer.setPadding(Ui.pxi(40f), Ui.pxi(40f), Ui.pxi(40f), Ui.pxi(40f))
    outer.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    d.setContentView(outer, ViewGroup.LayoutParams(Ui.pxi(widthU + 80f), ViewGroup.LayoutParams.WRAP_CONTENT))
    // blips inside the dialogs too: move, open and close (Back)
    outer.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
        if (old != null && now != null && old !== now && old.width > 0 && now.width > 0 && old.isAttachedToWindow) {
            val a = IntArray(2); val b = IntArray(2)
            old.getLocationInWindow(a); now.getLocationInWindow(b)
            val dx = Math.abs(a[0] + old.width / 2 - (b[0] + now.width / 2))
            val dy = Math.abs(a[1] + old.height / 2 - (b[1] + now.height / 2))
            Sfx.play(if (dx >= dy) Sfx.MOVE_H else Sfx.MOVE_V)
        }
    }
    d.setOnShowListener { Sfx.play(Sfx.OPEN) }
    d.setOnCancelListener { Sfx.play(Sfx.CLOSE) }
    return d
}

/** A menu row that keeps the menu open: its label is read again after each click. */
class MenuToggle(val label: () -> String, val at: Int, val onToggle: () -> Unit)

/** Small menu opened by press and hold. */
fun showMenu(context: Context, title: String, items: List<Pair<String, () -> Unit>>, toggle: MenuToggle? = null) {
    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    col.clipChildren = false
    val head = TextView(context)
    head.text = title; head.setTextColor(Ui.TEXT2); head.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(20f))
    head.setPadding(Ui.pxi(22f), Ui.pxi(6f), Ui.pxi(22f), Ui.pxi(10f))
    col.addView(head)
    lateinit var dialog: Dialog
    var first: View? = null
    fun addToggle() {
        if (toggle == null) return
        lateinit var tv: TextView
        val row = focusRow(context, toggle.label()) { toggle.onToggle(); tv.text = toggle.label() }
        tv = (row as ViewGroup).getChildAt(0) as TextView
        if (first == null) first = row
        col.addView(row)
    }
    items.forEachIndexed { i, (label, action) ->
        if (toggle != null && i == toggle.at) addToggle()
        val row = focusRow(context, label) { dialog.dismiss(); action() }
        if (first == null) first = row
        col.addView(row)
    }
    if (toggle != null && toggle.at >= items.size) addToggle()
    dialog = panelDialog(context, 420f, col)
    dialog.show()
    first?.requestFocus()
}

/** City search by name through the Open-Meteo geocoding API. No IP lookup. */
fun showCitySearch(context: Context, runOnUi: (Runnable) -> Unit, onPick: (City) -> Unit) {
    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    val head = TextView(context)
    head.text = "Weather city"; head.setTextColor(Ui.TEXT2); head.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(20f))
    head.setPadding(Ui.pxi(22f), Ui.pxi(6f), Ui.pxi(22f), Ui.pxi(10f))
    col.addView(head)
    val input = EditText(context)
    input.hint = "Type a city name"
    input.setHintTextColor(Ui.TEXT3); input.setTextColor(Ui.TEXT)
    input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(24f))
    input.inputType = InputType.TYPE_CLASS_TEXT
    input.imeOptions = EditorInfo.IME_ACTION_SEARCH
    input.setSingleLine(true)
    input.background = Ui.rounded(0x30FFFFFF, 14f)
    input.setPadding(Ui.pxi(20f), Ui.pxi(12f), Ui.pxi(20f), Ui.pxi(12f))
    col.addView(input)
    val results = LinearLayout(context)
    results.orientation = LinearLayout.VERTICAL
    val scroll = ScrollView(context)
    scroll.addView(results)
    col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(380f)))
    lateinit var dialog: Dialog
    var seq = 0

    fun status(text: String) {
        results.removeAllViews()
        val t = TextView(context)
        t.text = text; t.setTextColor(Ui.TEXT3); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(18f))
        t.setPadding(Ui.pxi(22f), Ui.pxi(14f), Ui.pxi(22f), Ui.pxi(14f))
        results.addView(t)
    }

    fun search() {
        val q = input.text.toString().trim()
        if (q.length < 2) { status("Type at least 2 letters"); return }
        val mine = ++seq
        status("Searching...")
        Thread {
            val found: List<City>? = try { WeatherRepo.search(q) } catch (e: Throwable) { null }
            runOnUi(Runnable {
                if (mine != seq) return@Runnable
                results.removeAllViews()
                if (found == null) { status("No connection to geocoding-api.open-meteo.com (check the internet connection)"); return@Runnable }
                if (found.isEmpty()) { status("No city found"); return@Runnable }
                for (c in found) {
                    results.addView(focusRow(context, c.name, listOf(c.region, c.country).filter { it.isNotBlank() }.joinToString(", ")) {
                        dialog.dismiss(); onPick(c)
                    })
                }
            })
        }.start()
    }
    input.setOnEditorActionListener { _, id, _ ->
        if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) { search(); true } else false
    }
    status("Type a name, then press Search on the keyboard")
    dialog = panelDialog(context, 640f, col)
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    dialog.show()
    input.requestFocus()
}

/** One line text input (URLs), confirmed with the keyboard Done key. */
fun showTextInput(context: Context, title: String, hint: String, allowEmpty: Boolean = false, initial: String = "", onOk: (String) -> Unit) {
    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    val head = TextView(context)
    head.text = title; head.setTextColor(Ui.TEXT2); head.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(20f))
    head.setPadding(Ui.pxi(22f), Ui.pxi(6f), Ui.pxi(22f), Ui.pxi(10f))
    col.addView(head)
    val input = EditText(context)
    input.hint = hint
    if (initial.isNotEmpty()) { input.setText(initial); input.setSelection(initial.length) }
    input.setHintTextColor(Ui.TEXT3); input.setTextColor(Ui.TEXT)
    input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(22f))
    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    input.setSingleLine(true)
    input.background = Ui.rounded(0x30FFFFFF, 14f)
    input.setPadding(Ui.pxi(20f), Ui.pxi(12f), Ui.pxi(20f), Ui.pxi(12f))
    col.addView(input)
    lateinit var dialog: Dialog
    input.setOnEditorActionListener { _, id, _ ->
        if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_SEARCH) {
            val v = input.text.toString().trim()
            if (v.isNotEmpty() || allowEmpty) { dialog.dismiss(); onOk(v) }
            true
        } else false
    }
    dialog = panelDialog(context, 760f, col)
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    dialog.show()
    input.requestFocus()
}

/** Reader popup for a news item: text only. Up and Down scroll, OK pages down (closes at the end), Back closes. */
fun showReader(context: Context, title: String, meta: String, body: String, link: String) {
    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    val head = TextView(context)
    head.text = title; head.setTextColor(Ui.TEXT); head.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(28f))
    head.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    head.maxLines = 3
    head.setPadding(Ui.pxi(22f), Ui.pxi(6f), Ui.pxi(22f), Ui.pxi(4f))
    col.addView(head)
    val m = TextView(context)
    m.text = meta; m.setTextColor(Ui.TEXT3); m.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(18f))
    m.setPadding(Ui.pxi(22f), 0, Ui.pxi(22f), Ui.pxi(8f))
    col.addView(m)
    val t = TextView(context)
    t.text = (if (body.isBlank()) "This feed only shares the title." else body) + "\n\n" + link
    t.setTextColor(Ui.TEXT2); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(22f))
    t.setLineSpacing(0f, 1.25f)
    t.setPadding(Ui.pxi(22f), Ui.pxi(12f), Ui.pxi(22f), Ui.pxi(20f))
    val scroll = ScrollView(context)
    scroll.addView(t)
    scroll.isFocusable = true
    scroll.isVerticalScrollBarEnabled = true
    scroll.isScrollbarFadingEnabled = false
    lateinit var dialog: Dialog
    scroll.setOnClickListener {
        if (scroll.canScrollVertically(1)) scroll.pageScroll(View.FOCUS_DOWN) else dialog.dismiss()
    }
    col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.pxi(540f)))
    val hint = TextView(context)
    hint.text = "Up and Down scroll, OK for next page, Back to close"
    hint.setTextColor(Ui.TEXT3); hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(15f))
    hint.setPadding(Ui.pxi(22f), Ui.pxi(10f), Ui.pxi(22f), Ui.pxi(4f))
    col.addView(hint)
    dialog = panelDialog(context, 1000f, col)
    dialog.show()
    scroll.requestFocus()
}
