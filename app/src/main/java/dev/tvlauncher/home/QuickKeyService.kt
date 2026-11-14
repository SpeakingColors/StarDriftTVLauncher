package dev.tvlauncher.home

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper for TVs that have a Quick Settings key on the remote and their own Quick Settings bar.
 * Tested on one Google TV model only; it depends on that bar's internals, so another model or an update may break it.
 *
 * On those TVs the key is not delivered to apps: the system sends it only to the bar's own app, which can stop
 * answering. That app always writes Settings.Global [FLAG] = 1 on a press. This service watches the flag and, when it
 * flips to 1, opens the bar itself, in any app. An accessibility service stays bound by the system and may start
 * activities from the background.
 *
 * It never reads or records key presses (no key filtering). It only watches window changes, and the live window list,
 * to avoid opening the bar twice (a second start closes it).
 * Off by default. Switched on from Settings; needs the one-time WRITE_SECURE_SETTINGS grant (docs/ADB.md).
 */
class QuickKeyService : AccessibilityService() {
    companion object {
        /** The TV's own Quick Settings bar app, its bar screen and the flag it writes on every press. */
        const val BAR_PACKAGE = "com.sony.dtv.smartui"
        const val BAR_ACTIVITY = "com.sony.dtv.smartui.settings.SmartSettingsActivity"
        const val FLAG = "smartui_launched"
    }

    private val ui = Handler(Looper.getMainLooper())
    private var last = 0L
    private var barSeen = false

    private val reset = Runnable { barSeen = false; put(0) }

    private val launch = Runnable {
        // Some apps let the bar's own app open it on the key-up; a second start would close it again.
        if (barUp()) {
            ui.removeCallbacks(reset)
            return@Runnable
        }
        try {
            startActivity(Intent().setComponent(ComponentName(BAR_PACKAGE, BAR_ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) { }
        ui.removeCallbacks(reset)
        ui.postDelayed(reset, 4000) // safety: never leave the flag at 1
    }

    /** True when the bar is already on screen: seen by window event, or found in the live window list. */
    private fun barUp(): Boolean = barSeen || try {
        windows.any { w -> w.root?.packageName?.toString() == BAR_PACKAGE }
    } catch (e: Throwable) { false }

    private val watcher = object : ContentObserver(ui) {
        override fun onChange(selfChange: Boolean) {
            if (get() != 1) return
            val now = SystemClock.uptimeMillis()
            if (now - last < 1000) return
            last = now
            ui.removeCallbacks(reset)
            ui.removeCallbacks(launch)
            barSeen = false
            // wait for the key-up (about 230 ms on the remote), which the bar's app would otherwise treat as a second tap
            ui.postDelayed(launch, 700)
            ui.postDelayed(reset, 4500)
        }
    }

    private fun get(): Int = try { Settings.Global.getInt(contentResolver, FLAG, 0) } catch (e: Throwable) { 0 }
    private fun put(v: Int) { try { Settings.Global.putInt(contentResolver, FLAG, v) } catch (e: Throwable) { } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        put(0)
        contentResolver.registerContentObserver(Settings.Global.getUriFor(FLAG), false, watcher)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == BAR_PACKAGE) {
            barSeen = true
            ui.removeCallbacks(reset) // the bar is up: keep the flag at 1 until it leaves
        } else if (barSeen && pkg != "android") {
            barSeen = false
            ui.removeCallbacks(reset)
            ui.postDelayed(reset, 300)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(watcher) } catch (e: Throwable) { }
        ui.removeCallbacks(reset)
        ui.removeCallbacks(launch)
        super.onDestroy()
    }
}
