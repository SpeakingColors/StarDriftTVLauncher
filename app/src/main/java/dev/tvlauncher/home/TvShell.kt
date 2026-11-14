package dev.tvlauncher.home

import android.content.Context
import android.provider.Settings
import java.io.File
import java.net.ConnectException

/**
 * Runs shell commands with the TV's own adbd (127.0.0.1:5555), which is the only way a normal app can
 * force-stop other apps or clear their cache. Needs the WRITE_SECURE_SETTINGS grant (same as the ADB switch)
 * and a one-time "Allow USB debugging" on the TV. If USB debugging was off, it is turned on for the
 * duration of the call only and switched off again afterwards.
 */
class TvShell(private val ctx: Context) {

    /** Carries a message that is fine to show to the user. */
    class Failure(message: String) : Exception(message)

    private fun adbOn(): Boolean =
        try { Settings.Global.getInt(ctx.contentResolver, "adb_enabled", 0) == 1 } catch (e: Throwable) { false }

    private fun setAdb(on: Boolean): Boolean =
        try { Settings.Global.putInt(ctx.contentResolver, "adb_enabled", if (on) 1 else 0) } catch (e: Throwable) { false }

    /** Never call on the main thread. [onAllowPrompt] runs when the TV asks the user to allow this app. */
    fun <T> session(onAllowPrompt: () -> Unit, block: (AdbClient) -> T): T {
        val wasOn = adbOn()
        android.util.Log.i("StardriftAdb", "session start, adb was on=$wasOn")
        if (!wasOn) {
            val ok = setAdb(true)
            android.util.Log.i("StardriftAdb", "turned adb on: ok=$ok, now on=" + adbOn())
            if (!ok) throw Failure("USB debugging is off and the app may not turn it on (see ADB setup)")
        }
        try {
            val key = try {
                AdbClient.loadOrCreateKey(File(ctx.filesDir, "adb_key.pk8"))
            } catch (e: Exception) {
                throw Failure("Could not create the ADB key")
            }
            val deadline = System.currentTimeMillis() + 12000L
            var client: AdbClient? = null
            while (client == null) {
                try {
                    client = AdbClient.connect(key, "127.0.0.1", 5555, 2000, 60000) { onAllowPrompt() }
                } catch (e: ConnectException) {
                    android.util.Log.i("StardriftAdb", "connect refused, retrying: " + e.message)
                    // adbd may still be starting after the switch was turned on
                    if (System.currentTimeMillis() > deadline) throw Failure("Could not reach the TV's ADB on port 5555")
                    Thread.sleep(500)
                } catch (e: java.net.SocketTimeoutException) {
                    throw Failure("No answer from the TV: allow USB debugging on the screen, then try again")
                } catch (e: Exception) {
                    throw Failure(e.message ?: "ADB connection failed")
                }
            }
            val connected: AdbClient = client!!
            val result: T = connected.use { block(it) }
            return result
        } finally {
            if (!wasOn) android.util.Log.i("StardriftAdb", "turned adb back off: ok=" + setAdb(false))
        }
    }
}
