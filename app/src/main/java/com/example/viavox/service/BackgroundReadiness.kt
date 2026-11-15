package com.example.viavox.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Many phones freeze or kill a foreground service once the screen turns off
 * unless the app is exempt from battery optimisation. Without the exemption
 * ViaVox speaks only while it is open on the screen.
 */
object BackgroundReadiness {
    fun isExemptFromBatteryOptimization(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /** Opens the system prompt, or the general settings list as a fallback. */
    fun requestExemption(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: SecurityException) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** When to bring GPS back to full precision, kept pure so it can be tested. */
object LocationPowerPolicy {
    const val MOVING_MPS = 1.5f
    /** Moving this far from where low power began means the vehicle left. */
    const val LEAVE_LOW_POWER_METERS = 30.0

    fun isMoving(speedMps: Float): Boolean = speedMps >= MOVING_MPS

    fun leftLowPowerPlace(
        anchor: com.example.viavox.model.GpsSpeed.Fix?,
        current: com.example.viavox.model.GpsSpeed.Fix
    ): Boolean = anchor != null &&
        com.example.viavox.model.NavigationMath.meters(
            anchor.latitude, anchor.longitude, current.latitude, current.longitude
        ) > LEAVE_LOW_POWER_METERS
}
