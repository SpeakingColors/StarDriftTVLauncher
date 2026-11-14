package dev.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.LinearLayout

/** A focusable row that draws the same white ring and soft glow as the tiles. Parents must not clip it. */
class GlowRow(context: Context) : LinearLayout(context) {
    var radiusU = 18f
    init { setWillNotDraw(false); isSoundEffectsEnabled = false }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (isFocused) Ui.drawFocus(c, 0f, 0f, width.toFloat(), height.toFloat(), Ui.px(radiusU))
    }
}

/** Same glow for a focusable frame, like the weather block. */
class GlowFrame(context: Context) : FrameLayout(context) {
    var radiusU = 18f
    init { setWillNotDraw(false); isSoundEffectsEnabled = false }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (isFocused) Ui.drawFocus(c, 0f, 0f, width.toFloat(), height.toFloat(), Ui.px(radiusU))
    }
}
