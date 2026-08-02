package com.feldman.ha.widgets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import com.feldman.ha.api.HomeAssistantWebSocket
import com.feldman.ha.api.getStoredWebSocket
import com.feldman.ha.widgets.generated.*
import kotlinx.coroutines.*

class WidgetSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: HomeAssistantWebSocket? = null
    private var syncJob: Job? = null
    private var lastTickTime = 0L
    private var lastInstantRefreshTime = 0L
    private var lastSubscriptionRefresh = 0L
    private var startTime = 0L
    private var tickCount = 0

    companion object {
        private const val CHANNEL_ID = "widget_sync_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "HA_SyncService"
        private const val HEALTHY_TICK_WINDOW_MS = 90_000L
        
        const val ACTION_WAKE = "com.feldman.ha.action.WAKE"
        const val ACTION_SLEEP = "com.feldman.ha.action.SLEEP"
        const val ACTION_RELOAD = "com.feldman.ha.action.RELOAD"
        const val ACTION_FORCE_UPDATE = "com.feldman.ha.action.FORCE_UPDATE"
    }

    override fun onCreate() {
        super.onCreate()
        startTime = System.currentTimeMillis()
        WidgetSyncLogger.log(this, "Service: onCreate")
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            WidgetSyncLogger.log(this, "Service: startForeground failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_WAKE
        WidgetSyncLogger.log(this, "Service: onStartCommand (startId=$startId, action=$action)")

        when (action) {
            ACTION_SLEEP -> {
                WidgetSyncLogger.log(this, "Service: Pausing Sync (Screen OFF)")
                syncJob?.cancel()
                webSocket?.disconnect()
                webSocket = null  // Force a clean WebSocket on next wake
                syncJob = null
            }
            ACTION_RELOAD -> {
                WidgetSyncLogger.log(this, "Service: ACTION_RELOAD received")
                syncJob?.cancel()
                webSocket?.disconnect()
                webSocket = null
                syncJob = null
                lastTickTime = 0 // Force restart in ensureWebSocketConnected
                ensureWebSocketConnected()
            }
            ACTION_FORCE_UPDATE -> {
                val widgetKey = intent?.getStringExtra(WidgetRefreshScheduler.EXTRA_WIDGET_KEY)
                val appWidgetId = if (intent?.hasExtra(WidgetRefreshScheduler.EXTRA_APP_WIDGET_ID) == true) 
                    intent.getIntExtra(WidgetRefreshScheduler.EXTRA_APP_WIDGET_ID, -1) else null
                
                serviceScope.launch {
                    Log.d(TAG, "Service: FORCE_UPDATE action for key=$widgetKey id=$appWidgetId")
                    updateFactoryWidgets(applicationContext, widgetKey, if (appWidgetId == -1) null else appWidgetId, refreshFromApi = true)
                    WidgetRefreshScheduler.requestProviderUpdate(applicationContext, widgetKey, appWidgetId)
                }
            }
            else -> {
                ensureWebSocketConnected()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Re-discover the configured widget entities and fold them into the live WebSocket
     * subscription. Throttled because WAKE fires very frequently (every onUpdate / screen-on).
     * updateEntities() itself no-ops when the set is unchanged, so this only re-subscribes
     * when a widget was actually added or removed.
     */
    private fun refreshSubscriptionThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastSubscriptionRefresh < 3_000L) return
        lastSubscriptionRefresh = now
        val ws = webSocket ?: return
        serviceScope.launch {
            runCatching {
                val ids = getAllConfiguredEntityIds(applicationContext)
                if (ids.isNotEmpty()) ws.updateEntities(ids)
            }
        }
    }

    private fun ensureWebSocketConnected() {
        val now = System.currentTimeMillis()

        if (!WidgetNetworkGate.canAttemptForegroundNetwork(applicationContext, "service ensure")) {
            WidgetSyncLogger.log(applicationContext, "Service: WebSocket start delayed by network backoff; scheduling retry")
            // If the sync loop isn't running, schedule a single retry so we don't stay stuck.
            if (syncJob?.isActive != true) {
                serviceScope.launch {
                    delay(30_000L)
                    ensureWebSocketConnected()
                }
            }
            return
        }
        
        // Instant refresh whenever wake/ensure is called (e.g. screen on), throttled to 10s
        if (now - lastInstantRefreshTime > 10_000L) {
            lastInstantRefreshTime = now
            serviceScope.launch {
                WidgetSyncLogger.log(applicationContext, "Service: Performing instant refresh on wake (throttled)")
                updateFactoryWidgets(applicationContext, null, null, refreshFromApi = true)
                WidgetRefreshScheduler.requestProviderUpdate(applicationContext)
            }
        } else {
            Log.d(TAG, "Instant refresh throttled (last: ${now - lastInstantRefreshTime}ms ago)")
        }
        if (syncJob?.isActive == true && (now - lastTickTime) < HEALTHY_TICK_WINDOW_MS) {
            Log.d(TAG, "Sync job already active and healthy")
            // The socket subscribes to the entity set discovered at connect time. Widgets added
            // afterwards (incl. system re-adds on boot/app-update, which skip the configure
            // activity) must be folded into the live subscription, or they never get WS updates.
            refreshSubscriptionThrottled()
            return
        }

        if (syncJob?.isActive == true) {
            WidgetSyncLogger.log(this, "Service: Loop stuck (last: ${now - lastTickTime}ms ago), restarting...")
            syncJob?.cancel()
        }

        lastTickTime = System.currentTimeMillis()
        syncJob = serviceScope.launch {
            try {
                delay(1000L) // Wait for DataStore commits if this is a reload
                val entityIds = getAllConfiguredEntityIds(applicationContext)
                if (entityIds.isEmpty()) {
                    WidgetSyncLogger.log(applicationContext, "Service: No entities configured, stopping sync loop")
                    Log.d(TAG, "No entities configured, stopping service")
                    stopSelf()
                    return@launch
                }

                if (webSocket == null) {
                    WidgetSyncLogger.log(applicationContext, "Service: Initializing WebSocket for ${entityIds.size} entities")
                    Log.d(TAG, "Initializing WebSocket for ${entityIds.size} entities: $entityIds")
                    webSocket = getStoredWebSocket(applicationContext, entityIds) { failure ->
                        WidgetNetworkGate.reportFailure(applicationContext, failure, "service websocket")
                    }
                } else {
                    Log.d(TAG, "WebSocket already exists, skipping init")
                }

                // Event listener loop
                val lastEntityUpdateTime = mutableMapOf<String, Long>()
                launch {
                    Log.d(TAG, "SyncJob: Starting WebSocket listener")
                    try {
                        webSocket?.stateChanges?.collect { haEntity ->
                            val now = System.currentTimeMillis()
                            val lastUpdate = lastEntityUpdateTime[haEntity.entity_id] ?: 0L
                            if (now - lastUpdate < 1000L) {
                                // Log.v(TAG, "Throttling update for ${haEntity.entity_id}")
                                return@collect
                            }
                            lastEntityUpdateTime[haEntity.entity_id] = now
                            
                            Log.d(TAG, "SyncJob: Received state change for ${haEntity.entity_id}")
                            processEntityUpdate(haEntity)
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "WebSocket listener cancelled")
                    } catch (e: Exception) {
                        WidgetSyncLogger.log(applicationContext, "Service: WebSocket collector failed: ${e.message}")
                        WidgetNetworkGate.reportFailure(applicationContext, e, "service collector")
                        if (WidgetNetworkGate.isNetworkFailure(e)) {
                            Log.w(TAG, "WebSocket collector network failure: ${e.message}")
                        } else {
                            Log.e(TAG, "WebSocket collector failed", e)
                        }
                        webSocket = null // Force re-init on next check
                    } finally {
                        Log.d(TAG, "SyncJob: WebSocket listener terminated")
                    }
                }
                
                // Fallback / Refresh loop
                launch {
                    while (isActive) {
                        delay(WidgetRefreshScheduler.REFRESH_INTERVAL_MS)
                        lastTickTime = System.currentTimeMillis()
                        tickCount++
                        
                        if (tickCount % 30 == 0) { // Every 10 mins (30 * 20s)
                            val uptimeMin = (System.currentTimeMillis() - startTime) / 60_000L
                            WidgetSyncLogger.log(applicationContext, "Service: Heartbeat - active for ${uptimeMin}m")
                        }

                        Log.d(TAG, "loop: periodic tick")
                        if (!WidgetNetworkGate.canAttemptForegroundNetwork(applicationContext, "service periodic")) {
                            continue
                        }
                        try {
                            updateFactoryWidgets(applicationContext, null, null, refreshFromApi = true)
                            WidgetRefreshScheduler.requestProviderUpdate(applicationContext)

                            // Keep the live WS subscription in sync with the current widget set.
                            runCatching {
                                val ids = getAllConfiguredEntityIds(applicationContext)
                                if (ids.isNotEmpty()) webSocket?.updateEntities(ids)
                            }

                            if (webSocket?.isConnected != true) {
                                WidgetSyncLogger.log(applicationContext, "Service: WebSocket disconnected, reconnecting...")
                                webSocket?.connect()
                            }
                        } catch (e: Exception) {
                            WidgetNetworkGate.reportFailure(applicationContext, e, "service loop")
                            if (WidgetNetworkGate.isNetworkFailure(e)) {
                                Log.w(TAG, "loop: network failure: ${e.message}")
                            } else {
                                Log.e(TAG, "loop: error", e)
                            }
                        }
                    }
                }

                webSocket?.connect()
                WidgetSyncLogger.log(applicationContext, "Service: WebSocket connection initiated")
            } catch (e: CancellationException) {
                Log.d(TAG, "Service sync loop cancelled")
            } catch (e: Exception) {
                WidgetSyncLogger.log(applicationContext, "Service: Fatal loop error: ${e.message}")
                delay(10_000)
                syncJob = null // Allow restart
            }
        }
    }

    private suspend fun processEntityUpdate(haEntity: com.feldman.ha.data.HAEntity) {
        val glanceManager = GlanceAppWidgetManager(applicationContext)
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)

        for (entry in FactoryWidgetProviderCatalog.entries) {
            val provider = ComponentName(packageName, entry.receiverClassName)
            val ids = appWidgetManager.getAppWidgetIds(provider)
            for (id in ids) {
                val glanceId = runCatching { glanceManager.getGlanceIdBy(id) }.getOrNull() ?: continue
                val state = runCatching {
                    getAppWidgetState(applicationContext, CachedPreferencesGlanceStateDefinition, glanceId)
                }.getOrNull()

                val isPrimary = state?.get(FACT_ENTITY_ID) == haEntity.entity_id
                val isExtra = state?.asMap()?.any {
                    it.key.name.startsWith("fact_extra_entity_") && it.value == haEntity.entity_id
                } == true
                // Entities referenced by the state template / custom features (targets, value
                // templates, visibility conditions) must also refresh the widget when they change.
                val referencedIds = buildSet {
                    addAll(templateEntityIds(state?.get(FACT_STATE_TEMPLATE)))
                    addAll(customFeatureEntityIds(parseCustomFeatures(state?.get(FACT_CUSTOM_FEATURES))))
                    addAll(buttonLabelBlockEntityIds(parseButtonLabelBlocks(state?.get(FACT_BUTTON_LABEL_BLOCKS))))
                }
                val isReferenced = haEntity.entity_id in referencedIds

                if (!isPrimary && !isExtra && !isReferenced) continue

                runCatching {
                    updateAppWidgetState(applicationContext, CachedPreferencesGlanceStateDefinition, glanceId) { p ->
                        val mutable = p.toMutablePreferences()
                        val spec = WidgetRegistry.spec(entry.widgetKey) ?: return@updateAppWidgetState p

                        if (isPrimary) {
                            mapHAEntityToPreferences(haEntity, spec, mutable)
                        } else if (isExtra) {
                            haEntity.attributes.forEach { (k, v) ->
                                val key = stringPreferencesKey("attr_$k")
                                when (v) {
                                    is Number -> mutable[key] = v.toString()
                                    is String -> mutable[key] = v
                                    is Boolean -> mutable[key] = v.toString()
                                }
                            }
                            spec.entityPickers.forEach { picker ->
                                val savedEid = mutable[stringPreferencesKey("fact_extra_entity_${picker.id}")]
                                if (savedEid == haEntity.entity_id) {
                                    mutable[stringPreferencesKey("attr_${picker.id}")] = haEntity.state
                                }
                            }
                        }

                        if (isReferenced) {
                            val byId = parseRefEntities(mutable[FACT_REF_ENTITIES])
                                .associateBy { it.entity_id }.toMutableMap()
                            byId[haEntity.entity_id] = haEntity
                            mutable[FACT_REF_ENTITIES] = serializeRefEntities(byId.values.toList())
                        }

                        val countKey = intPreferencesKey("fact_update_count")
                        mutable[countKey] = (mutable[countKey] ?: 0) + 1
                        mutable
                    }
                    FactoryGlanceAppWidget(entry.widgetKey).update(applicationContext, glanceId)
                    Log.d(TAG, "applied state change to widgetKey=${entry.widgetKey} appWidgetId=$id")
                }.onFailure { Log.e(TAG, "failed to apply state change to widgetKey=${entry.widgetKey} appWidgetId=$id", it) }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Widget Synchronization",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Home Assistant widgets updated in real-time"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Home Assistant Sync Active")
            .setContentText("Keeping your widgets up to date...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        WidgetSyncLogger.log(this, "Service: onDestroy")
        try {
            webSocket?.disconnect()
            syncJob?.cancel()
            serviceScope.cancel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
        super.onDestroy()
    }
}
