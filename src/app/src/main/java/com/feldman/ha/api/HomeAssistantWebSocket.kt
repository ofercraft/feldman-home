package com.feldman.ha.api

import android.util.Log
import com.feldman.ha.data.HAEntity
import com.google.gson.Gson
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class HomeAssistantWebSocket(
    private val url: String,
    private val token: String,
    initialEntityIds: List<String>,
    private val onFailure: ((Throwable) -> Unit)? = null,
    label: String = "?"
) {
    private companion object {
        val sharedClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val entityIds = initialEntityIds.toMutableList()
    // label distinguishes the concurrent sockets in logs (UI dashboard vs widget service),
    // which otherwise share one tag and are impossible to tell apart.
    private val TAG = "HA_WS/$label"
    private val client = sharedClient
    
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var disconnecting = false
    @Volatile private var authenticated = false
    // Bumped on every connect()/disconnect(). Each listener captures the generation it was
    // created with; callbacks that fire after the generation moved on belong to a socket that
    // has been superseded (e.g. a connect() that raced with disconnect()) and must not mutate
    // shared state, or they corrupt the live connection.
    @Volatile private var generation = 0
    @Volatile var isConnected = false
        private set
    private var currentSubscriptionId = 1
    private var activeSubscriptionId: Int? = null

    // SharedFlow to emit state changes to observers
    private val _stateChanges = MutableSharedFlow<HAEntity>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stateChanges = _stateChanges.asSharedFlow()

    private val _connectedEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val connectedEvents = _connectedEvents.asSharedFlow()

    @Synchronized
    fun updateEntities(newIds: List<String>) {
        // Compare as sets: discovery order can differ between passes, and only a real
        // membership change should trigger a re-subscribe.
        if (entityIds.toSet() == newIds.toSet()) return
        Log.d(TAG, "updateEntities: ${entityIds.size} -> ${newIds.size} entities")
        entityIds.clear()
        entityIds.addAll(newIds)
        // Only re-subscribe once the socket is authenticated; otherwise the
        // initial subscribe is driven by the auth_ok handler.
        if (authenticated) webSocket?.let { subscribe(it) }
    }

    @Synchronized
    fun connect() {
        if (isConnected || webSocket != null) return
        disconnecting = false
        val gen = ++generation

        val wsString = wsUrl(url)
        if (wsString == "demo" || url.trim().equals("demo", ignoreCase = true) || token == "demo") {
            Log.d(TAG, "Demo mode active - skipping real WebSocket connection")
            return
        }

        try {
            Log.d(TAG, "Connecting to $wsString")
            val request = Request.Builder().url(wsString).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // A late onOpen from a socket we already abandoned must not resurrect isConnected.
                    if (gen != generation) { webSocket.cancel(); return }
                    Log.d(TAG, "WebSocket Connected successfully")
                    disconnecting = false
                    isConnected = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != generation) return
                    handleMessage(webSocket, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // Ignore the close of a superseded socket; nulling webSocket here would clobber
                    // the connection a newer connect() just established.
                    if (gen != generation) return
                    Log.d(TAG, "WebSocket Closed: $code $reason")
                    disconnecting = false
                    isConnected = false
                    authenticated = false
                    activeSubscriptionId = null
                    this@HomeAssistantWebSocket.webSocket = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (gen != generation) return
                    val expectedDisconnectAbort = disconnecting &&
                        t is SocketException &&
                        t.message?.contains("abort", ignoreCase = true) == true

                    if (expectedDisconnectAbort) {
                        Log.d(TAG, "WebSocket closed during disconnect: ${t.message}")
                    } else if (isExpectedNetworkFailure(t)) {
                        Log.w(TAG, "WebSocket network failure: ${t.message}")
                        onFailure?.invoke(t)
                    } else {
                        Log.e(TAG, "WebSocket Failure: ${t.message}", t)
                        onFailure?.invoke(t)
                    }
                    disconnecting = false
                    isConnected = false
                    authenticated = false
                    activeSubscriptionId = null
                    this@HomeAssistantWebSocket.webSocket = null
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to connect WebSocket for $wsString: ${e.message}", e)
            disconnecting = false
            isConnected = false
            authenticated = false
            activeSubscriptionId = null
            webSocket = null
            onFailure?.invoke(e)
        }
    }

    @Synchronized
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        generation++  // invalidate the current listener so its late callbacks are ignored
        disconnecting = true
        authenticated = false
        activeSubscriptionId = null
        webSocket?.close(1000, "App disconnected")
        webSocket = null
        isConnected = false
    }
    
    private fun wsUrl(httpUrl: String): String {
        val trimmed = httpUrl.trim()
        if (trimmed.isBlank() || trimmed.equals("demo", ignoreCase = true) || trimmed.startsWith("demo", ignoreCase = true)) {
            return "demo"
        }
        var ws = trimmed
        if (!ws.startsWith("http://", ignoreCase = true) &&
            !ws.startsWith("https://", ignoreCase = true) &&
            !ws.startsWith("ws://", ignoreCase = true) &&
            !ws.startsWith("wss://", ignoreCase = true)) {
            ws = "ws://$ws"
        } else {
            ws = ws.replace("http://", "ws://", ignoreCase = true)
                .replace("https://", "wss://", ignoreCase = true)
        }
        ws = ws.replace(Regex("(/api)?(/lovelace)?/?$"), "")
        if (!ws.endsWith("/")) ws += "/"
        ws += "api/websocket"
        return ws
    }

    private fun isExpectedNetworkFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is SocketException -> return true
                is IOException -> {
                    val msg = current.message.orEmpty()
                    if (msg.contains("Software caused connection abort", ignoreCase = true) ||
                        msg.contains("No address associated with hostname", ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return false
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            
            when (type) {
                "auth_required" -> {
                    Log.d(TAG, "Auth Required. Sending token...")
                    val authMsg = JSONObject().apply {
                        put("type", "auth")
                        put("access_token", token)
                    }
                    ws.send(authMsg.toString())
                }
                "auth_ok" -> {
                    Log.d(TAG, "Auth OK. Subscribing to triggers...")
                    authenticated = true
                    subscribe(ws)
                    _connectedEvents.tryEmit(Unit)
                }
                "auth_invalid" -> {
                    Log.e(TAG, "Auth Invalid: ${json.optString("message")}")
                    disconnect()
                }
                "event" -> {
                    val event = json.optJSONObject("event") ?: return
                    val variables = event.optJSONObject("variables") ?: return
                    val trigger = variables.optJSONObject("trigger") ?: return
                    val toState = trigger.optJSONObject("to_state") ?: return
                    
                    val entityId = toState.optString("entity_id")
                    if (entityId.isNotEmpty() && entityIds.contains(entityId)) {
                        val gson = Gson()
                        val haEntity = gson.fromJson(toState.toString(), HAEntity::class.java)
                        Log.d(TAG, "State changed: $entityId -> ${haEntity.state}")
                        _stateChanges.tryEmit(haEntity)
                    }
                }
                "result" -> {
                    if (json.optBoolean("success")) {
                        Log.d(TAG, "Subscription successful for id ${json.optInt("id")}")
                    } else {
                        Log.e(TAG, "Subscription failed: ${json.optJSONObject("error")?.optString("message")}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun subscribe(ws: WebSocket) {
        if (entityIds.isEmpty()) return

        // Tear down the previous trigger first. Without this, every re-subscribe leaks a
        // subscription on the HA side and we receive duplicate events for the old entities.
        activeSubscriptionId?.let { oldId ->
            val unsub = JSONObject().apply {
                put("id", currentSubscriptionId++)
                put("type", "unsubscribe_events")
                put("subscription", oldId)
            }
            ws.send(unsub.toString())
            activeSubscriptionId = null
        }

        // We use subscribe_trigger with the state platform to get state changes for specific entities
        val subId = currentSubscriptionId++
        val triggerObj = JSONObject().apply {
            put("platform", "state")
            put("entity_id", org.json.JSONArray(entityIds))
        }

        val subMsg = JSONObject().apply {
            put("id", subId)
            put("type", "subscribe_trigger")
            put("trigger", triggerObj)
        }

        activeSubscriptionId = subId
        Log.d(TAG, "Subscribing (id=$subId) to ${entityIds.size} entities")
        ws.send(subMsg.toString())
    }
}
