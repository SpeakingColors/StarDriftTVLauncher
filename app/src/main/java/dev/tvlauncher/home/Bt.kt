package dev.tvlauncher.home

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Bluetooth for the Home pane: paired devices, which ones are connected, scanning and pairing.
 *
 * What Android lets a normal app do (checked against the Android reference):
 *  - list paired devices, read their connection state, scan, start pairing (createBond): yes
 *  - connect or disconnect a paired device: no public API (BluetoothA2dp has no connect/disconnect for apps)
 *  - turn Bluetooth on or off: not for apps targeting Android 13 or newer (BluetoothAdapter.enable/disable always fail)
 * Those two go through the TV's own Bluetooth settings.
 */
object Bt {
    // profile ids: HEADSET 1, A2DP 2, HID host 4 (remotes, keyboards, gamepads; hidden constant, stable value), LE audio 22
    private val PROFILES: IntArray
        get() = if (Build.VERSION.SDK_INT >= 33) intArrayOf(1, 2, 4, 22) else intArrayOf(1, 2, 4)

    private val proxies = HashMap<Int, BluetoothProfile>()
    private var asked = false

    fun adapter(ctx: Context): BluetoothAdapter? = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (e: Throwable) { null }

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Android 12+ needs the "Nearby devices" permission even to read the paired list. */
    fun canList(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    /** Scanning: "Nearby devices" on Android 12+, location on older versions (a rule of Android, nothing is located). */
    fun canScan(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(ctx, Manifest.permission.BLUETOOTH_SCAN)
        else granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun listPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else arrayOf()

    fun scanPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Binds the profile services once; [ready] runs each time one of them answers. */
    fun bind(ctx: Context, ready: () -> Unit) {
        val a = adapter(ctx) ?: return
        if (!canList(ctx)) return
        if (asked) { ready(); return }
        asked = true
        for (id in PROFILES) {
            try {
                a.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; ready() }
                    override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
                }, id)
            } catch (e: Throwable) { }
        }
    }

    /** Addresses connected on any audio or input profile. */
    @Suppress("MissingPermission")
    fun connected(): Set<String> {
        val out = HashSet<String>()
        for (p in proxies.values) try { p.connectedDevices.forEach { out.add(it.address) } } catch (e: Throwable) { }
        return out
    }

    @Suppress("MissingPermission")
    fun paired(ctx: Context): List<BluetoothDevice> = try {
        (adapter(ctx)?.bondedDevices ?: emptySet()).sortedBy { name(it).lowercase() }
    } catch (e: Throwable) { emptyList() }

    @Suppress("MissingPermission")
    fun name(d: BluetoothDevice): String = try {
        (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name ?: d.address
    } catch (e: Throwable) { d.address }

    /** Game controllers: Bluetooth class "peripheral, joystick or gamepad", or a controller name (some report a wrong class). */
    @Suppress("MissingPermission")
    fun isPad(d: BluetoothDevice): Boolean {
        val dc = try { d.bluetoothClass?.deviceClass ?: 0 } catch (e: Throwable) { 0 }
        if ((dc and 0x1F00) == 0x0500 && ((dc and 0x3C) == 0x04 || (dc and 0x3C) == 0x08)) return true
        val n = name(d).lowercase()
        return listOf("controller", "gamepad", "dualsense", "dualshock", "xbox", "joy-con", "8bitdo").any { n.contains(it) }
    }

    /** Disconnects through the TV's own ADB (see BtShell). Never on the main thread. Returns BtShell's line. */
    fun adbDisconnect(ctx: Context, address: String): String = try {
        val line = "CLASSPATH=" + ctx.applicationInfo.sourceDir + " app_process /system/bin dev.tvlauncher.home.BtShell disconnect " + address
        val out = TvShell(ctx).session({ }) { c -> c.shell(line, 30000) }
        out.lines().map { it.trim() }.lastOrNull { it.startsWith("OK") || it.startsWith("ERR") } ?: ("ERR " + out.trim().take(120))
    } catch (e: Throwable) { "ERR " + (e.message ?: e.javaClass.simpleName) }

    @Suppress("MissingPermission")
    fun kind(d: BluetoothDevice): String {
        val c = try { d.bluetoothClass } catch (e: Throwable) { null } ?: return "Device"
        return when (c.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
            BluetoothClass.Device.Major.PERIPHERAL -> "Remote, keyboard or controller"
            BluetoothClass.Device.Major.PHONE -> "Phone"
            BluetoothClass.Device.Major.COMPUTER -> "Computer"
            BluetoothClass.Device.Major.WEARABLE -> "Wearable"
            else -> "Device"
        }
    }
}
