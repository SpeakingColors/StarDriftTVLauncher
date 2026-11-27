package dev.tvlauncher.home;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;

/**
 * Runs inside the TV's own ADB shell, not inside the app:
 *   CLASSPATH=&lt;this apk&gt; app_process /system/bin dev.tvlauncher.home.BtShell connect AA:BB:CC:DD:EE:FF
 *
 * The shell user (com.android.shell, uid 2000) holds BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED and
 * MODIFY_PHONE_STATE, which the system-only calls below need. A normal app cannot make these calls:
 *   BluetoothDevice.connect() / disconnect()   (Android 13+, @SystemApi; Android 12 has
 *   BluetoothAdapter.connectAllEnabledProfiles / disconnectAllEnabledProfiles instead)
 *   BluetoothDevice.removeBond()               (forget the device)
 * Prints one line: "OK <value>" or "ERR <reason>".
 */
public final class BtShell {
    private static final String SHELL_PKG = "com.android.shell";

    public static void main(String[] args) {
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            if (args.length < 2) { System.out.println("ERR usage: connect|disconnect|forget <address>"); return; }
            BluetoothAdapter ad = adapter();
            if (ad == null) { System.out.println("ERR no Bluetooth adapter"); return; }
            BluetoothDevice d = ad.getRemoteDevice(args[1].toUpperCase());
            Object r;
            switch (args[0]) {
                case "connect":
                case "disconnect":
                    try {
                        r = BluetoothDevice.class.getMethod(args[0]).invoke(d);
                    } catch (NoSuchMethodException e) {
                        String m = args[0].equals("connect") ? "connectAllEnabledProfiles" : "disconnectAllEnabledProfiles";
                        r = BluetoothAdapter.class.getMethod(m, BluetoothDevice.class).invoke(ad, d);
                    }
                    break;
                case "forget":
                    r = BluetoothDevice.class.getMethod("removeBond").invoke(d);
                    break;
                default:
                    System.out.println("ERR unknown command " + args[0]);
                    return;
            }
            // connect/disconnect return a BluetoothStatusCodes value (0 = success); removeBond returns true/false
            System.out.println("OK " + r);
        } catch (InvocationTargetException e) {
            System.out.println("ERR " + e.getCause());
        } catch (Throwable t) {
            System.out.println("ERR " + t);
        } finally {
            System.out.flush();
            System.exit(0);
        }
    }

    /** An adapter that speaks as the shell user, so the Bluetooth service checks the shell's permissions. */
    private static BluetoothAdapter adapter() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return BluetoothAdapter.getDefaultAdapter();
        final AttributionSource src = new AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PKG).build();
        Context ctx = new ContextWrapper(null) {
            @Override public AttributionSource getAttributionSource() { return src; }
            @Override public String getPackageName() { return SHELL_PKG; }
            @Override public String getOpPackageName() { return SHELL_PKG; }
        };
        // BluetoothManager(Context) is hidden but public; it only reads the attribution from the context
        BluetoothManager bm = BluetoothManager.class.getConstructor(Context.class).newInstance(ctx);
        return bm.getAdapter();
    }
}
