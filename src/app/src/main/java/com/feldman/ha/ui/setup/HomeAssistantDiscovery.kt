package com.feldman.ha.ui.setup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.charset.StandardCharsets

data class DiscoveredHomeAssistantInstance(
    val name: String,
    val url: String,
    val host: String,
    val port: Int
)

sealed interface HomeAssistantDiscoveryEvent {
    data object Started : HomeAssistantDiscoveryEvent
    data class Found(val instance: DiscoveredHomeAssistantInstance) : HomeAssistantDiscoveryEvent
    data class Error(val message: String) : HomeAssistantDiscoveryEvent
}

private const val HomeAssistantServiceType = "_home-assistant._tcp."

fun discoverHomeAssistantInstances(context: Context): Flow<HomeAssistantDiscoveryEvent> = callbackFlow {
    val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    if (nsdManager == null) {
        trySend(HomeAssistantDiscoveryEvent.Error("Network service discovery is not available on this device."))
        close()
        return@callbackFlow
    }

    val handler = Handler(Looper.getMainLooper())
    val pendingServices = ArrayDeque<NsdServiceInfo>()
    var resolving = false
    var discoveryStarted = false

    fun resolveNext() {
        if (resolving || pendingServices.isEmpty()) return

        resolving = true
        val service = pendingServices.removeFirst()
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                serviceInfo.toDiscoveredInstance()?.let {
                    trySend(HomeAssistantDiscoveryEvent.Found(it))
                }
                handler.post {
                    resolving = false
                    resolveNext()
                }
            }
        }

        runCatching {
            nsdManager.resolveService(service, resolveListener)
        }.onFailure {
            resolving = false
            trySend(HomeAssistantDiscoveryEvent.Error(it.localizedMessage ?: "Could not resolve discovered service."))
            resolveNext()
        }
    }

    val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            discoveryStarted = true
            trySend(HomeAssistantDiscoveryEvent.Started)
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (!service.serviceType.contains("_home-assistant._tcp", ignoreCase = true)) return
            handler.post {
                pendingServices.addLast(service)
                resolveNext()
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(serviceType: String) {
            discoveryStarted = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            trySend(HomeAssistantDiscoveryEvent.Error("Local discovery failed. Error code: $errorCode"))
            runCatching { nsdManager.stopServiceDiscovery(this) }
            close()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryStarted = false
        }
    }

    runCatching {
        nsdManager.discoverServices(
            HomeAssistantServiceType,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )
    }.onFailure {
        trySend(HomeAssistantDiscoveryEvent.Error(it.localizedMessage ?: "Could not start local discovery."))
        close(it)
    }

    awaitClose {
        handler.post {
            if (discoveryStarted) {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun NsdServiceInfo.toDiscoveredInstance(): DiscoveredHomeAssistantInstance? {
    val advertisedUrl = attributes.readString("internal_url")
        ?: attributes.readString("base_url")
        ?: attributes.readString("url")
        ?: attributes.readString("external_url")

    val resolvedHost = host?.hostAddress ?: host?.hostName ?: return null
    val resolvedPort = port.takeIf { it > 0 } ?: 8123
    val fallbackScheme = if (resolvedPort == 443) "https" else "http"
    val fallbackUrl = buildString {
        append(fallbackScheme)
        append("://")
        append(resolvedHost.asUrlHost())
        if (resolvedPort != 80 && resolvedPort != 443) {
            append(":")
            append(resolvedPort)
        }
    }

    return DiscoveredHomeAssistantInstance(
        name = serviceName.ifBlank { "Home Assistant" },
        url = normalizeHomeAssistantUrl(advertisedUrl ?: fallbackUrl),
        host = resolvedHost,
        port = resolvedPort
    )
}

private fun Map<String, ByteArray>.readString(key: String): String? =
    this[key]
        ?.let { String(it, StandardCharsets.UTF_8).trim() }
        ?.takeIf { it.isNotBlank() }

private fun String.asUrlHost(): String =
    if (contains(":") && !startsWith("[")) "[$this]" else this

fun normalizeHomeAssistantUrl(input: String): String {
    var url = input.trim()
    if (url.isBlank()) return ""
    if (url.equals("demo", ignoreCase = true)) return "demo"
    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        url = "http://$url"
    }
    return url.replace(Regex("(\\/api)?(\\/lovelace)?\\/?$"), "")
}

fun normalizeOptionalNetworkUrl(input: String): String {
    var url = input.trim()
    if (url.isBlank()) return ""
    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        url = "http://$url"
    }
    return url.trimEnd('/')
}
