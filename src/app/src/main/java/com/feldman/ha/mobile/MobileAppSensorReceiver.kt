package com.feldman.ha.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pushes sensor states as soon as something interesting happens, rather than waiting out the
 * 15-minute worker.
 *
 * Only actions exempt from Android 8's implicit-broadcast restrictions can be declared in the
 * manifest, which is why this is limited to power, battery and boot. The companion app has the
 * same constraint and solves the rest — screen on/off, ringer mode, Do Not Disturb — with a
 * receiver registered at runtime by a long-lived foreground service. Without that service those
 * sensors update on the periodic pass instead, which is also what the companion app falls back to
 * when its persistent notification is turned off.
 *
 * The work itself is handed to [MobileAppSensorUpdateWorker]: a receiver has a few seconds to
 * return, and this needs the network.
 */
class MobileAppSensorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (!MobileAppRegistration.isRegistered(appContext)) return

        when (intent.action) {
            // A reboot clears WorkManager's schedule for the app, and a package replace restarts
            // the process; both need the periodic pass put back.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MobileAppSensorUpdateWorker.schedule(appContext)
                // Neither survives a reboot or a reinstall on its own.
                MobileAppSensorService.start(appContext)
            }
        }

        MobileAppSensorUpdateWorker.enqueueNow(appContext)
    }
}
