package com.feldman.ha.widgets

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min

object WidgetNetworkGate {
    private const val TAG = "HA_NetworkGate"
    private const val BASE_BACKOFF_MS = 10_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val LOG_THROTTLE_MS = 30_000L

    @Volatile private var backoffUntilMs = 0L
    @Volatile private var failureCount = 0
    @Volatile private var lastLogTimeMs = 0L

    @Synchronized
    fun canAttemptNetwork(context: Context, reason: String, respectBackoff: Boolean = true): Boolean {
        return canAttempt(
            context = context,
            reason = reason,
            respectBackoff = respectBackoff,
            requireValidated = true,
            blockRestrictedBackground = true
        )
    }

    @Synchronized
    fun canAttemptForegroundNetwork(context: Context, reason: String, respectBackoff: Boolean = true): Boolean {
        return canAttempt(
            context = context,
            reason = reason,
            respectBackoff = respectBackoff,
            requireValidated = true,
            blockRestrictedBackground = false
        )
    }

    @Synchronized
    fun canAttemptVisibleWidgetNetwork(context: Context, reason: String, respectBackoff: Boolean = true): Boolean {
        return canAttempt(
            context = context,
            reason = reason,
            respectBackoff = respectBackoff,
            requireValidated = true,
            blockRestrictedBackground = false
        )
    }

    @Synchronized
    fun canAttemptUserAction(context: Context, reason: String, respectBackoff: Boolean = true): Boolean {
        val state = networkState(context, requireValidated = false, blockRestrictedBackground = false)
        if (!state.usable) {
            Log.d(TAG, "Proceeding with user HA action despite ${state.description} ($reason)")
        }
        return true
    }

    private fun canAttempt(
        context: Context,
        reason: String,
        respectBackoff: Boolean,
        requireValidated: Boolean,
        blockRestrictedBackground: Boolean
    ): Boolean {
        val now = System.currentTimeMillis()
        if (respectBackoff && now < backoffUntilMs) {
            logThrottled("Skipping HA network for ${backoffUntilMs - now}ms ($reason)")
            return false
        }

        val state = networkState(context, requireValidated, blockRestrictedBackground)
        if (!state.usable) {
            logThrottled("Skipping HA network: ${state.description} ($reason)")
            return false
        }

        return true
    }

    @Synchronized
    fun reportSuccess() {
        if (failureCount > 0 || backoffUntilMs > 0L) {
            Log.d(TAG, "HA network recovered")
        }
        failureCount = 0
        backoffUntilMs = 0L
    }

    @Synchronized
    fun reportFailure(context: Context, throwable: Throwable, reason: String) {
        if (isExpectedCancellation(throwable) || !isNetworkFailure(throwable)) return
        val state = networkState(context, requireValidated = false, blockRestrictedBackground = false)
        enterBackoff("$reason failed (${throwable.localizedMessage ?: throwable.javaClass.simpleName}, ${state.description})", throwable)
    }

    fun isExpectedCancellation(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is CancellationException) return true
            current = current.cause
        }
        return false
    }

    fun isNetworkFailure(throwable: Throwable): Boolean {
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

    private data class NetworkState(
        val usable: Boolean,
        val description: String
    )

    private fun networkState(
        context: Context,
        requireValidated: Boolean,
        blockRestrictedBackground: Boolean
    ): NetworkState {
        return try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: return NetworkState(true, "network state unavailable")
            val activeNetwork = connectivity.activeNetwork
            val network = activeNetwork ?: connectivity.allNetworks.firstOrNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (!requireValidated || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            } ?: return NetworkState(false, if (requireValidated) "no active or validated network" else "no active network")
            val capabilities = connectivity.getNetworkCapabilities(network)
                ?: return NetworkState(false, "active network has no capabilities")
            val networkLabel = if (network == activeNetwork) "active network" else "validated background network"

            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return NetworkState(false, "$networkLabel has no internet capability")
            }
            if (requireValidated && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return NetworkState(false, "$networkLabel is not validated")
            }
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                return NetworkState(false, "$networkLabel is suspended")
            }
            if (
                blockRestrictedBackground &&
                connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                !isAppForeground()
            ) {
                return NetworkState(false, "background mobile data is restricted")
            }

            NetworkState(true, if (requireValidated) "$networkLabel validated" else "$networkLabel available")
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read network state; allowing HA network attempt", e)
            NetworkState(true, "network state unavailable")
        }
    }

    private fun isAppForeground(): Boolean {
        return runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)
    }

    private fun enterBackoff(reason: String, throwable: Throwable?) {
        failureCount += 1
        val delayMs = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L shl (failureCount - 1).coerceAtMost(3)))
        backoffUntilMs = maxOf(backoffUntilMs, System.currentTimeMillis() + delayMs)
        val message = "Backing off HA network for ${delayMs / 1000}s: $reason"
        if (throwable != null) {
            logThrottled(message, throwable)
        } else {
            logThrottled(message)
        }
    }

    private fun logThrottled(message: String, throwable: Throwable? = null) {
        val now = System.currentTimeMillis()
        if (now - lastLogTimeMs < LOG_THROTTLE_MS) return
        lastLogTimeMs = now
        if (throwable == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, throwable)
        }
    }
}
