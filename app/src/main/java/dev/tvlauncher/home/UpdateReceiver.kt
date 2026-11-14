package dev.tvlauncher.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Gets the installer's answer: shows Android's confirmation screen, or tells why the update did not go in. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val next = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (next != null) {
                    next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(next) } catch (e: Throwable) { Toast.makeText(ctx, "Could not open the installer", Toast.LENGTH_LONG).show() }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            else -> Toast.makeText(ctx, "Update not installed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
    }
}
