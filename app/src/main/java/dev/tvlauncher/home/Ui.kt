package dev.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout

/** Shared look and helpers. Design units are 1600 x 900; [Ui.s] is pixels per unit. */
object Ui {
    const val ACCENT = 0xFFB69CFF.toInt()
    const val TEXT = 0xFFEEF0F7.toInt()
    const val TEXT2 = 0xFFC3CAD9.toInt()
    const val TEXT3 = 0xFFAEB6C7.toInt()
    const val CARD = 0xB80E0C1E.toInt()
    const val STROKE = 0x2EFFFFFF

    var s = 1f

    fun init(context: Context) {
        val m = context.resources.displayMetrics
        s = Math.min(m.widthPixels / 1600f, m.heightPixels / 900f)
    }

    fun px(u: Float): Float = u * s
    fun pxi(u: Float): Int = Math.round(u * s)

    fun rounded(color: Int, radiusU: Float, strokeColor: Int = 0, strokeU: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = px(radiusU)
            if (strokeU > 0f) setStroke(Math.max(1, pxi(strokeU)), strokeColor)
        }

    fun place(v: View, xu: Float, yu: Float, wu: Float = -1f, hu: Float = -1f): View {
        val lp = FrameLayout.LayoutParams(if (wu < 0) FrameLayout.LayoutParams.WRAP_CONTENT else pxi(wu),
            if (hu < 0) FrameLayout.LayoutParams.WRAP_CONTENT else pxi(hu))
        lp.leftMargin = pxi(xu)
        lp.topMargin = pxi(yu)
        v.layoutParams = lp
        return v
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCCFFFFFF.toInt() }
    private val focusRect = RectF()

    /** The mockup focus look: white 3 unit ring and a soft accent glow around it. Draws outside the bounds, so parents must not clip. */
    fun drawFocus(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        val n = 16
        glowPaint.strokeWidth = px(2.6f)
        for (k in n downTo 1) {
            val off = px(3f + k * 1.8f)
            val f = 1f - k / (n + 1f)
            val a = Math.round(255f * 0.55f * f * f)
            glowPaint.color = (ACCENT and 0x00FFFFFF) or (a shl 24)
            focusRect.set(l - off, t - off, r + off, b + off)
            c.drawRoundRect(focusRect, radius + off, radius + off, glowPaint)
        }
        val o = px(1.5f)
        ringPaint.strokeWidth = px(3f)
        focusRect.set(l - o, t - o, r + o, b + o)
        c.drawRoundRect(focusRect, radius + o, radius + o, ringPaint)
    }

    /** Lift and accent ring while focused. */
    fun focusable(v: View, lift: Float = 1.07f) {
        v.isFocusable = true
        v.isClickable = true
        v.isSoundEffectsEnabled = false   // the launcher plays its own blips
        v.setOnFocusChangeListener { view, focused ->
            view.translationZ = if (focused) 8f else 0f
            view.animate().scaleX(if (focused) lift else 1f).scaleY(if (focused) lift else 1f).setDuration(140).start()
            view.invalidate()
        }
    }

    fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
        Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
        Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
        Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f))
}
