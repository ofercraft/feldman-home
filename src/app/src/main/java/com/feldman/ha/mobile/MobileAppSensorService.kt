package com.feldman.ha.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.feldman.ha.R
import com.feldman.ha.api.HomeAssistantAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps sensors that only announce themselves through non-exempt broadcasts up to date.
 *
 * Android 8 blocks manifest receivers for most implicit broadcasts, so power save mode, screen
 * on/off, ringer mode and Do Not Disturb can only be observed by a receiver registered at runtime
 * — which needs a process that is actually alive. Screen brightness has no broadcast at all and is
 * watched through a [ContentObserver] on Settings.System instead.
 *
 * This is the same trade the official companion app makes for its "persistent connection" setting:
 * a foreground notification buys prompt updates. With the service off, these sensors still move,
 * just on the 15-minute worker.
 */
class MobileAppSensorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null
    private var receiver: BroadcastReceiver? = null
    private var settingsObserver: ContentObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceivers()
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarted by the system after being killed: the receivers are re-registered in onCreate.
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        settingsObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val instance = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = scheduleUpdate()
        }
        receiver = instance
        ContextCompat_registerNotExported(this, instance, filter)
    }

    private fun observeSettings() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = scheduleUpdate()
        }
        settingsObserver = observer
        runCatching {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        }.onFailure { Log.w(TAG, "Could not observe system settings", it) }
    }

    /**
     * Coalesces bursts before sending.
     *
     * ACTION_BATTERY_CHANGED fires every time the level or temperature moves, and dragging the
     * brightness slider emits a change per pixel. Sending on each one would mean hundreds of
     * webhook calls a minute, so events collapse into one update a couple of seconds after the
     * last of them.
     */
    private fun scheduleUpdate() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching {
                MobileAppSensors.updateEnabledSensors(applicationContext)
            }.onFailure { Log.w(TAG, "Live sensor update failed", it) }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sensor updates",
                // Min: the notification is a requirement of running in the foreground, not
                // something worth interrupting anyone for.
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sending sensors to Home Assistant")
            .setContentText("Keeps phone sensors up to date in real time")
            .setSmallIcon(R.drawable.ic_home)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "MobileAppSensors"
        private const val CHANNEL_ID = "mobile_app_sensor_updates"
        private const val NOTIFICATION_ID = 4711
        private const val DEBOUNCE_MS = 2_000L
        private const val KEY_LIVE_UPDATES = "mobile_sensor_live_updates"

        /** Whether the user wants prompt updates at the cost of a persistent notification. */
        fun isEnabled(context: Context): Boolean =
            HomeAssistantAuth.prefs(context).getBoolean(KEY_LIVE_UPDATES, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            HomeAssistantAuth.prefs(context).edit { putBoolean(KEY_LIVE_UPDATES, enabled) }
            if (enabled) start(context) else stop(context)
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            if (!isEnabled(appContext)) {
                Log.i(TAG, "service: not starting, live updates are switched off")
                return
            }
            if (!MobileAppRegistration.isRegistered(appContext)) {
                Log.i(TAG, "service: not starting, this phone is not registered")
                return
            }
            runCatching {
                appContext.startForegroundService(Intent(appContext, MobileAppSensorService::class.java))
            }.onFailure { Log.w(TAG, "Could not start the live sensor service", it) }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, MobileAppSensorService::class.java))
            }
        }
    }
}

/**
 * All the actions here are system broadcasts, so the receiver must not be exported. Android 14
 * requires the flag to be stated explicitly rather than inferred.
 */
private fun ContextCompat_registerNotExported(
    context: Context,
    receiver: BroadcastReceiver,
    filter: IntentFilter
) {
    androidx.core.content.ContextCompat.registerReceiver(
        context,
        receiver,
        filter,
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
    )
}
