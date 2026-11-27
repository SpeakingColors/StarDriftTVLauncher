package dev.tvlauncher.home

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Idle game controllers: a controller left on can stay connected for days. When no controller button was pressed
 * for the time chosen in the Bluetooth popup ("Idle controllers"), connected controllers are disconnected through
 * the TV's ADB (Bt.adbDisconnect), and most controllers then turn themselves off.
 *
 * Privacy: the key filter only notes WHEN a game controller button was last pressed. It does not record which keys,
 * keeps nothing, and lets every key through. Remote and keyboard keys are ignored. Stick moves are not key events and
 * are not seen, so the shortest choice is 15 minutes.
 * Off by default; switched on (and off) by the Idle controllers row, which needs the one-time WRITE_SECURE_SETTINGS
 * grant (docs/ADB.md), and the TV's ADB for the disconnect itself.
 */
class PadIdleService : AccessibilityService() {
    companion object {
        /** Last button press from a game controller (elapsedRealtime). */
        @Volatile var lastPadInput = 0L
    }

    private val ui = Handler(Looper.getMainLooper())
    private val padSince = HashMap<String, Long>()
    private val padTried = HashSet<String>()

    private val padTick = object : Runnable {
        override fun run() {
            try { checkPads() } catch (e: Throwable) { }
            ui.postDelayed(this, 60_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ui.postDelayed(padTick, 60_000L)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val src = event.source
        if ((src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(event.keyCode)) lastPadInput = SystemClock.elapsedRealtime()
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

    override fun onDestroy() {
        ui.removeCallbacks(padTick)
        super.onDestroy()
    }

    private fun checkPads() {
        val min = getSharedPreferences("tvlauncher", MODE_PRIVATE).getInt("pad_idle_min", 0)
        if (min <= 0 || !Bt.canList(this)) return
        Bt.bind(this) { }
        val now = SystemClock.elapsedRealtime()
        val conn = Bt.connected()
        val pads = Bt.paired(this).filter { it.address in conn && Bt.isPad(it) }
        val live = pads.map { it.address }.toSet()
        padSince.keys.retainAll(live)
        padTried.retainAll(live)
        for (d in pads) {
            val since = padSince.getOrPut(d.address) { now }
            if (now - maxOf(lastPadInput, since) < min * 60_000L || !padTried.add(d.address)) continue
            val name = Bt.name(d)
            Thread {
                val res = Bt.adbDisconnect(this, d.address)
                android.util.Log.i("StardriftAdb", "idle pad $name: $res")
                if (res == "OK 0") ui.post {
                    try { android.widget.Toast.makeText(this, "$name disconnected after $min idle minutes", android.widget.Toast.LENGTH_LONG).show() } catch (e: Throwable) { }
                }
            }.start()
        }
    }
}
