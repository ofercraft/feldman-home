package com.feldman.ha.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import com.feldman.ha.data.HAEntity
import com.feldman.ha.api.HomeAssistantWebSocket
import com.feldman.ha.api.getStoredWebSocket
import com.feldman.ha.widgets.generated.FactoryWidgetProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object WidgetSyncEngine {
    private const val TAG = "HA_SyncEngine"
    private const val HEALTHY_TICK_WINDOW_MS = 90_000L
    private const val INSTANT_REFRESH_THROTTLE_MS = 10_000L
    private const val PERIODIC_REFRESH_MS = WidgetRefreshScheduler.REFRESH_INTERVAL_MS
    private const val RELOAD_THROTTLE_MS = 15_000L
    private const val START_GRACE_MS = 30_000L
    private const val STALE_LOOP_MS = 3 * PERIODIC_REFRESH_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var webSocket: HomeAssistantWebSocket? = null
    @Volatile private var syncJob: Job? = null
    @Volatile private var refreshJob: Job? = null
    @Volatile private var lastTickTime = 0L
    @Volatile private var lastStartAttemptTime = 0L
    @Volatile private var starting = false
    private var lastInstantRefreshTime = 0L
    private var lastReloadTime = 0L
    private var startTime = 0L
    private var tickCount = 0

    fun isHealthy(): Boolean {
        val now = System.currentTimeMillis()
        return syncJob?.isActive == true && now - lastTickTime < HEALTHY_TICK_WINDOW_MS
    }

    @Synchronized
    fun ensureRunning(
        context: Context,
        reason: String,
        forceRefresh: Boolean = false
    ) {
        val appCtx = context.applicationContext
        WidgetSyncLogger.log(appCtx, "Engine: disabled; visible Glance sessions own refresh ($reason)")
        stop(appCtx, "engine disabled")
        return

        val now = System.currentTimeMillis()
        if (startTime == 0L) startTime = now

        maybeRefreshNow(appCtx, reason, forceRefresh)

        if (isHealthy()) {
            Log.d(TAG, "ensureRunning($reason): already healthy")
            return
        }

        val existingJob = syncJob
        if (existingJob?.isActive == true) {
            if (now - lastTickTime < STALE_LOOP_MS) {
                Log.d(TAG, "ensureRunning($reason): loop already active (${now - lastTickTime}ms since tick)")
                return
            }

            WidgetSyncLogger.log(appCtx, "Engine: loop stale, restarting (${now - lastTickTime}ms since tick)")
            webSocket?.disconnect()
            webSocket = null
            syncJob = null
            existingJob.cancel()
        }

        if (starting && now - lastStartAttemptTime < START_GRACE_MS) {
            Log.d(TAG, "ensureRunning($reason): start already in progress")
            return
        }

        if (!WidgetNetworkGate.canAttemptNetwork(appCtx, "sync loop:$reason")) {
            WidgetSyncLogger.log(appCtx, "Engine: WebSocket start delayed by network backoff ($reason)")
            return
        }

        WidgetSyncLogger.log(appCtx, "Engine: starting WebSocket loop ($reason)")
        starting = true
        lastStartAttemptTime = now
        lastTickTime = now
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(1_000L)
                val entityIds = getAllConfiguredEntityIds(appCtx)
                if (entityIds.isEmpty()) {
                    WidgetSyncLogger.log(appCtx, "Engine: no entities configured, loop not started")
                    return@launch
                }

                WidgetSyncLogger.log(appCtx, "Engine: initializing WebSocket for ${entityIds.size} entities")
                webSocket = getStoredWebSocket(appCtx, entityIds) { failure ->
                    WidgetNetworkGate.reportFailure(appCtx, failure, "websocket")
                }
                val socket = webSocket ?: run {
                    WidgetSyncLogger.log(appCtx, "Engine: WebSocket unavailable, check HA URL/token")
                    return@launch
                }

                val lastEntityUpdateTime = mutableMapOf<String, Long>()
                launch {
                    Log.d(TAG, "collector: starting")
                    try {
                        socket.stateChanges.collect { haEntity ->
                            val eventTime = System.currentTimeMillis()
                            val lastUpdate = lastEntityUpdateTime[haEntity.entity_id] ?: 0L
                            if (eventTime - lastUpdate < 1_000L) return@collect
                            lastEntityUpdateTime[haEntity.entity_id] = eventTime

                            Log.d(TAG, "collector: state change ${haEntity.entity_id} -> ${haEntity.state}")
                            processEntityUpdate(appCtx, haEntity)
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "collector cancelled")
                    } catch (e: Exception) {
                        WidgetSyncLogger.log(appCtx, "Engine: WebSocket collector failed: ${e.message}")
                        WidgetNetworkGate.reportFailure(appCtx, e, "collector")
                        if (WidgetNetworkGate.isNetworkFailure(e)) {
                            Log.w(TAG, "collector network failure: ${e.message}")
                        } else {
                            Log.e(TAG, "collector failed", e)
                        }
                        webSocket = null
                    } finally {
                        Log.d(TAG, "collector: terminated")
                    }
                }

                launch {
                    while (isActive) {
                        delay(PERIODIC_REFRESH_MS)
                        lastTickTime = System.currentTimeMillis()
                        tickCount++

                        if (tickCount % 30 == 0) {
                            val uptimeMin = (System.currentTimeMillis() - startTime) / 60_000L
                            WidgetSyncLogger.log(appCtx, "Engine: heartbeat active for ${uptimeMin}m")
                        }

                        if (!WidgetNetworkGate.canAttemptNetwork(appCtx, "periodic refresh")) {
                            continue
                        }

                        runCatching {
                            updateFactoryWidgets(appCtx, null, null, refreshFromApi = true)
                            WidgetRefreshScheduler.requestProviderUpdate(appCtx)

                            if (socket.isConnected != true) {
                                WidgetSyncLogger.log(appCtx, "Engine: WebSocket disconnected, reconnecting")
                                socket.connect()
                            }
                        }.onFailure {
                            WidgetNetworkGate.reportFailure(appCtx, it, "periodic refresh")
                            if (WidgetNetworkGate.isNetworkFailure(it)) {
                                Log.w(TAG, "periodic refresh network failure: ${it.message}")
                            } else {
                                Log.e(TAG, "periodic refresh failed", it)
                            }
                        }
                    }
                }

                socket.connect()
                starting = false
                WidgetSyncLogger.log(appCtx, "Engine: WebSocket connection initiated")
            } catch (e: CancellationException) {
                Log.d(TAG, "sync loop cancelled")
            } catch (e: Exception) {
                WidgetSyncLogger.log(appCtx, "Engine: fatal loop error: ${e.message}")
                WidgetNetworkGate.reportFailure(appCtx, e, "sync loop")
                if (WidgetNetworkGate.isNetworkFailure(e)) {
                    Log.w(TAG, "sync loop network failure: ${e.message}")
                } else {
                    Log.e(TAG, "fatal loop error", e)
                }
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                synchronized(this@WidgetSyncEngine) {
                    starting = false
                    if (syncJob === currentJob) {
                        webSocket = null
                        syncJob = null
                    }
                }
            }
        }
        syncJob = newJob
        newJob.start()
    }

    @Synchronized
    fun reload(context: Context, reason: String) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastReloadTime < RELOAD_THROTTLE_MS) {
            WidgetSyncLogger.log(appCtx, "Engine: reload throttled ($reason)")
            ensureRunning(appCtx, "reload-throttled:$reason", forceRefresh = false)
            return
        }
        lastReloadTime = now

        WidgetSyncLogger.log(appCtx, "Engine: reload requested ($reason)")
        syncJob?.cancel()
        webSocket?.disconnect()
        syncJob = null
        webSocket = null
        lastTickTime = 0L
        ensureRunning(appCtx, "reload:$reason", forceRefresh = true)
    }

    @Synchronized
    fun stop(context: Context, reason: String) {
        val appCtx = context.applicationContext
        WidgetSyncLogger.log(appCtx, "Engine: stopping WebSocket loop ($reason)")
        syncJob?.cancel()
        webSocket?.disconnect()
        syncJob = null
        webSocket = null
        lastTickTime = 0L
    }

    fun forceUpdate(context: Context, widgetKey: String?, appWidgetId: Int?) {
        val appCtx = context.applicationContext
        scope.launch {
            WidgetSyncLogger.log(appCtx, "Engine: force update key=$widgetKey id=$appWidgetId")
            if (!WidgetNetworkGate.canAttemptNetwork(appCtx, "forceUpdate")) {
                return@launch
            }
            runCatching {
                updateFactoryWidgets(appCtx, widgetKey, appWidgetId, refreshFromApi = true)
                WidgetRefreshScheduler.requestProviderUpdate(appCtx, widgetKey, appWidgetId)
                ensureRunning(appCtx, "forceUpdate", forceRefresh = false)
            }.onFailure {
                WidgetNetworkGate.reportFailure(appCtx, it, "forceUpdate")
                if (WidgetNetworkGate.isNetworkFailure(it)) {
                    Log.w(TAG, "force update network failure: ${it.message}")
                } else {
                    Log.e(TAG, "force update failed", it)
                }
            }
        }
    }

    private fun maybeRefreshNow(context: Context, reason: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastInstantRefreshTime <= INSTANT_REFRESH_THROTTLE_MS) {
            Log.d(TAG, "instant refresh throttled (${now - lastInstantRefreshTime}ms since last)")
            return
        }

        if (refreshJob?.isActive == true) {
            Log.d(TAG, "instant refresh skipped; refresh already running")
            return
        }

        if (!WidgetNetworkGate.canAttemptNetwork(context, "instant refresh:$reason")) {
            return
        }

        lastInstantRefreshTime = now
        refreshJob = scope.launch {
            WidgetSyncLogger.log(context, "Engine: instant refresh ($reason)")
            runCatching {
                updateFactoryWidgets(context, null, null, refreshFromApi = true)
                WidgetRefreshScheduler.requestProviderUpdate(context)
            }.onFailure {
                WidgetNetworkGate.reportFailure(context, it, "instant refresh")
                if (WidgetNetworkGate.isNetworkFailure(it)) {
                    Log.w(TAG, "instant refresh network failure: ${it.message}")
                } else {
                    Log.e(TAG, "instant refresh failed", it)
                }
            }
        }
    }

    private suspend fun processEntityUpdate(context: Context, haEntity: HAEntity) {
        val appCtx = context.applicationContext
        val glanceManager = GlanceAppWidgetManager(appCtx)
        val appWidgetManager = AppWidgetManager.getInstance(appCtx)

        for (entry in FactoryWidgetProviderCatalog.entries) {
            val provider = ComponentName(appCtx.packageName, entry.receiverClassName)
            val ids = appWidgetManager.getAppWidgetIds(provider)
            for (id in ids) {
                val glanceId = runCatching { glanceManager.getGlanceIdBy(id) }.getOrNull() ?: continue
                val state = runCatching {
                    getAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId)
                }.getOrNull()

                val isPrimary = state?.get(FACT_ENTITY_ID) == haEntity.entity_id
                val isExtra = state?.asMap()?.any {
                    it.key.name.startsWith("fact_extra_entity_") && it.value == haEntity.entity_id
                } == true
                // Entities referenced by the state template / custom features (targets, templates,
                // visibility conditions) must also refresh the widget when they change.
                val referencedIds = buildSet {
                    addAll(templateEntityIds(state?.get(FACT_STATE_TEMPLATE)))
                    addAll(customFeatureEntityIds(parseCustomFeatures(state?.get(FACT_CUSTOM_FEATURES))))
                    addAll(buttonLabelBlockEntityIds(parseButtonLabelBlocks(state?.get(FACT_BUTTON_LABEL_BLOCKS))))
                }
                val isReferenced = haEntity.entity_id in referencedIds

                if (!isPrimary && !isExtra && !isReferenced) continue

                runCatching {
                    updateAppWidgetState(appCtx, CachedPreferencesGlanceStateDefinition, glanceId) { prefs ->
                        val mutable = prefs.toMutablePreferences()
                        val spec = WidgetRegistry.spec(entry.widgetKey) ?: return@updateAppWidgetState prefs

                        if (isPrimary) {
                            mapHAEntityToPreferences(haEntity, spec, mutable)
                        } else if (isExtra) {
                            haEntity.attributes.forEach { (keyName, value) ->
                                val key = stringPreferencesKey("attr_$keyName")
                                when (value) {
                                    is Number -> mutable[key] = value.toString()
                                    is String -> mutable[key] = value
                                    is Boolean -> mutable[key] = value.toString()
                                }
                            }

                            spec.entityPickers.forEach { picker ->
                                val savedEntityId = mutable[stringPreferencesKey("fact_extra_entity_${picker.id}")]
                                if (savedEntityId == haEntity.entity_id) {
                                    mutable[stringPreferencesKey("attr_${picker.id}")] = haEntity.state
                                }
                            }
                        }

                        if (isReferenced) {
                            val byId = parseRefEntities(mutable[FACT_REF_ENTITIES]).associateBy { it.entity_id }.toMutableMap()
                            byId[haEntity.entity_id] = haEntity
                            mutable[FACT_REF_ENTITIES] = serializeRefEntities(byId.values.toList())
                        }

                        val countKey = intPreferencesKey("fact_update_count")
                        mutable[countKey] = (mutable[countKey] ?: 0) + 1
                        mutable
                    }
                    FactoryGlanceAppWidget(entry.widgetKey).update(appCtx, glanceId)
                    Log.d(TAG, "applied state change to widgetKey=${entry.widgetKey} appWidgetId=$id")
                }.onFailure {
                    Log.e(TAG, "failed to apply state change to widgetKey=${entry.widgetKey} appWidgetId=$id", it)
                }
            }
        }
    }
}
