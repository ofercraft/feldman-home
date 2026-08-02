package com.feldman.ha.ui.pages

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.api.HomeAssistantOAuthSession
import com.feldman.ha.ui.setup.DiscoveredHomeAssistantInstance
import com.feldman.ha.ui.setup.HomeAssistantAccountLoginDialog
import com.feldman.ha.ui.setup.HomeAssistantDiscoveryEvent
import com.feldman.ha.ui.setup.discoverHomeAssistantInstances
import com.feldman.ha.ui.setup.normalizeHomeAssistantUrl
import com.feldman.ha.ui.setup.rememberRotatingSymbolPainter
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import com.feldman.motion.SettingsScaffold
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How the app reaches Home Assistant: instance URL, credentials, and the local-network scan that
 * fills the URL in for you. Grouped on one page because discovery, login and token are three
 * routes to the same outcome — a working connection — and picking one affects the others.
 *
 * The Frigate URL deliberately lives on the cameras page instead; it is a separate service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsPage(
    initialBaseUrl: String,
    initialToken: String,
    frigateUrl: String,
    onBack: () -> Unit,
    onSave: (baseUrl: String, token: String, frigateUrl: String, oauthSession: HomeAssistantOAuthSession?) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember(initialBaseUrl) { mutableStateOf(normalizeHomeAssistantUrl(initialBaseUrl)) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var oauthSession by remember(context) { mutableStateOf(HomeAssistantAuth.storedOAuthSession(context)) }
    var showToken by remember { mutableStateOf(false) }
    var showAccountLogin by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(true) }
    var isScanning by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<DiscoveredHomeAssistantInstance>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    fun reportError(message: String) {
        statusMessage = message
        statusIsError = true
    }

    fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(context, SettingsLocalNetworkPermission) == PackageManager.PERMISSION_GRANTED

    fun startScan() {
        scanJob?.cancel()
        discovered = emptyList()
        statusMessage = null
        isScanning = true
        scanJob = scope.launch {
            val found = mutableListOf<DiscoveredHomeAssistantInstance>()
            val collectionJob = launch {
                runCatching {
                    discoverHomeAssistantInstances(context).collect { event ->
                        when (event) {
                            HomeAssistantDiscoveryEvent.Started -> Unit
                            is HomeAssistantDiscoveryEvent.Error -> reportError(event.message)
                            is HomeAssistantDiscoveryEvent.Found -> {
                                if (found.none { it.url == event.instance.url }) {
                                    found += event.instance
                                    discovered = found.toList()
                                }
                            }
                        }
                    }
                }.onFailure {
                    reportError(it.localizedMessage ?: "Local discovery stopped.")
                }
            }
            delay(6_000)
            collectionJob.cancel()
            isScanning = false
            if (found.isEmpty() && statusMessage == null) {
                reportError("No Home Assistant instance was found on this network.")
            }
        }
    }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScan()
        else reportError("Local network access is required to search for Home Assistant.")
    }

    fun requestScan() {
        if (hasLocalNetworkPermission()) startScan()
        else localNetworkPermissionLauncher.launch(SettingsLocalNetworkPermission)
    }

    fun saveSettings() {
        val normalizedBaseUrl = normalizeHomeAssistantUrl(baseUrl)
        val normalizedToken = token.trim()
        when {
            normalizedBaseUrl.isBlank() -> return reportError("Enter your Home Assistant URL.")
            normalizedToken.isBlank() ->
                return reportError("Log in with Home Assistant or paste a long-lived access token.")
        }
        view.clearFocus()
        statusMessage = null
        onSave(normalizedBaseUrl, normalizedToken, frigateUrl, oauthSession)
    }

    DisposableEffect(Unit) { onDispose { scanJob?.cancel() } }

    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(
            title = "Connection",
            scaffoldModifier = Modifier.fillMaxSize(),
            topBar = { SettingsTopBar("Connection", onBack) }
        ) {
            title("Instance")
            section {
                item(padding = 16.dp) {
                    SettingsTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            oauthSession = null
                            statusMessage = null
                        },
                        label = "Instance URL",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        )
                    )
                }
                pageItem(
                    title = if (isScanning) "Searching…" else "Search local network",
                    description = "Find Home Assistant with mDNS discovery",
                    icon = rememberRotatingSymbolPainter(
                        if (isScanning) "sync" else "travel_explore",
                        isRotating = isScanning
                    ),
                    backgroundColor = colorScheme.secondaryContainer,
                    iconColor = colorScheme.onSecondaryContainer,
                    onClick = { if (!isScanning) requestScan() }
                )
                discovered.forEach { instance ->
                    pageItem(
                        key = instance.url,
                        title = instance.name,
                        description = instance.url,
                        icon = rememberSymbolPainter("home_assistant"),
                        backgroundColor = colorScheme.tertiaryContainer,
                        iconColor = colorScheme.onTertiaryContainer,
                        onClick = {
                            baseUrl = instance.url
                            oauthSession = null
                            statusMessage = "Using ${instance.name}. Save changes to keep it."
                            statusIsError = false
                        }
                    )
                }
            }

            title("Sign in")
            section {
                item(padding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MotionButton(
                            text = "Log in with Home Assistant",
                            icon = "login",
                            onClick = {
                                val normalizedBaseUrl = normalizeHomeAssistantUrl(baseUrl)
                                if (normalizedBaseUrl.isBlank() || normalizedBaseUrl.equals("demo", ignoreCase = true)) {
                                    reportError("Enter your Home Assistant URL before logging in.")
                                } else {
                                    statusMessage = null
                                    showAccountLogin = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            fontSize = 18.sp,
                            iconSize = 20.dp,
                            defaultState = MotionButtonState(
                                backgroundColor = colorScheme.primary,
                                contentColor = colorScheme.onPrimary,
                                outlineWidth = 0.dp,
                                outlineColor = colorScheme.outline
                            )
                        )
                        Text(
                            text = "Or keep using a manually created long-lived access token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        SettingsTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                oauthSession = null
                                statusMessage = null
                            },
                            label = "Long-lived access token",
                            singleLine = false,
                            minLines = 3,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showToken = !showToken }) {
                                    Text(if (showToken) "Hide" else "Show")
                                }
                            }
                        )
                    }
                }
            }

            if (statusMessage != null) {
                section {
                    item(padding = 16.dp) {
                        Text(
                            text = statusMessage.orEmpty(),
                            color = if (statusIsError) colorScheme.error else colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Clears the save dock, which floats over the scrolling content.
            item { Spacer(Modifier.height(128.dp)) }
        }

        SettingsSaveDock(
            onSave = ::saveSettings,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showAccountLogin) {
            HomeAssistantAccountLoginDialog(
                baseUrl = normalizeHomeAssistantUrl(baseUrl),
                onConnected = { session ->
                    token = session.accessToken
                    oauthSession = session
                    showToken = false
                    statusMessage = "Logged in with Home Assistant. Save changes to keep it."
                    statusIsError = false
                },
                onDismiss = { showAccountLogin = false },
                onError = { reportError(it) }
            )
        }
    }
}
