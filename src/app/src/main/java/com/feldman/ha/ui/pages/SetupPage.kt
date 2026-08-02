package com.feldman.ha.ui.pages

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.feldman.ha.api.HomeAssistantOAuthSession
import com.feldman.ha.api.provideHAApi
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import com.feldman.ha.ui.setup.DiscoveredHomeAssistantInstance
import com.feldman.ha.ui.setup.HomeAssistantAccountLoginDialog
import com.feldman.ha.ui.setup.HomeAssistantDiscoveryEvent
import com.feldman.ha.ui.setup.discoverHomeAssistantInstances
import com.feldman.ha.ui.setup.normalizeHomeAssistantUrl
import com.feldman.ha.ui.setup.normalizeOptionalNetworkUrl
import com.feldman.ha.ui.setup.rememberRotatingSymbolPainter

private const val AccessLocalNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
private const val DefaultHomeAssistantUrl = "http://homeassistant.local:8123"

private enum class SetupStep(val title: String) {
    Instance("Instance"),
    Token("Login"),
    Cameras("Cameras")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SetupPage(
    initialBaseUrl: String,
    initialToken: String,
    initialFrigateUrl: String,
    canDismiss: Boolean,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String, token: String, frigateUrl: String, oauthSession: HomeAssistantOAuthSession?) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    var stepIndex by remember { mutableIntStateOf(0) }
    var baseUrl by remember(initialBaseUrl) {
        mutableStateOf(normalizeHomeAssistantUrl(initialBaseUrl.ifBlank { DefaultHomeAssistantUrl }))
    }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var oauthSession by remember { mutableStateOf<HomeAssistantOAuthSession?>(null) }
    var frigateUrl by remember(initialFrigateUrl) { mutableStateOf(initialFrigateUrl) }
    var showToken by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var showAccountLogin by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<DiscoveredHomeAssistantInstance>>(emptyList()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    val steps = remember { SetupStep.entries }
    val currentStep = steps[stepIndex]
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardProgress by animateFloatAsState(
        targetValue = if (imeBottom > 0) 1f else 0f,
        label = "setupKeyboardProgress"
    )

    fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(context, AccessLocalNetworkPermission) == PackageManager.PERMISSION_GRANTED

    fun startScan() {
        scanJob?.cancel()
        errorMessage = null
        discovered = emptyList()
        isScanning = true
        scanJob = scope.launch {
            val found = mutableListOf<DiscoveredHomeAssistantInstance>()
            val collectionJob = launch {
                try {
                    discoverHomeAssistantInstances(context).collect { event ->
                        when (event) {
                            HomeAssistantDiscoveryEvent.Started -> Unit
                            is HomeAssistantDiscoveryEvent.Error -> errorMessage = event.message
                            is HomeAssistantDiscoveryEvent.Found -> {
                                if (found.none { it.url == event.instance.url }) {
                                    found += event.instance
                                    discovered = found.toList()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        errorMessage = e.localizedMessage ?: "Local discovery stopped."
                    }
                }
            }
            delay(6_000)
            collectionJob.cancel()
            isScanning = false
            if (found.isEmpty() && errorMessage == null) {
                errorMessage = "No Home Assistant instance was found on this network."
            }
        }
    }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startScan()
        } else {
            errorMessage = "Local network access is required to search for Home Assistant."
        }
    }

    fun requestScan() {
        if (hasLocalNetworkPermission()) {
            startScan()
        } else {
            localNetworkPermissionLauncher.launch(AccessLocalNetworkPermission)
        }
    }

    fun saveSetup() {
        val normalizedBaseUrl = normalizeHomeAssistantUrl(baseUrl)
        val normalizedToken = token.trim()
        val normalizedFrigate = normalizeOptionalNetworkUrl(frigateUrl)

        when {
            normalizedBaseUrl.isBlank() -> {
                errorMessage = "Enter your Home Assistant URL."
                stepIndex = SetupStep.Instance.ordinal
                return
            }
            normalizedToken.isBlank() -> {
                errorMessage = "Log in with Home Assistant or paste a long-lived access token."
                stepIndex = SetupStep.Token.ordinal
                return
            }
        }

        view.clearFocus()
        errorMessage = null
        isSaving = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    provideHAApi(normalizedToken, normalizedBaseUrl).getStates()
                }
            }.fold(
                onSuccess = {
                    isSaving = false
                    onSave(normalizedBaseUrl, normalizedToken, normalizedFrigate, oauthSession)
                },
                onFailure = { error ->
                    isSaving = false
                    errorMessage = setupErrorMessage(error)
                    if (error is HttpException && error.code() == 401) {
                        stepIndex = SetupStep.Token.ordinal
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanJob?.cancel() }
    }

    BackHandler {
        if (canDismiss) onDismiss()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier
                .padding(horizontal = 12.dp)
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canDismiss) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }

                    Text(
                        "Setup",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(Modifier.size(48.dp))
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Connect Home Assistant",
                    fontSize = 30.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                AnimatedVisibility(
                    visible = imeBottom == 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = 1f - keyboardProgress },
                    enter = slideInVertically(initialOffsetY = { -it / 2 }) +
                        expandVertically(expandFrom = Alignment.Top) +
                        fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it / 3 }) +
                        shrinkVertically(shrinkTowards = Alignment.Top) +
                        fadeOut()
                ) {
                    Text(
                        "to Feldman Home",
                        fontSize = 30.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(22.dp))

                StepIndicator(steps = steps, activeIndex = stepIndex)

                Spacer(Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Crossfade(targetState = currentStep, label = "setupStep") { step ->
                            when (step) {
                                SetupStep.Instance -> InstanceStep(
                                    baseUrl = baseUrl,
                                    onBaseUrlChange = {
                                        baseUrl = it
                                        oauthSession = null
                                        errorMessage = null
                                    },
                                    discovered = discovered,
                                    isScanning = isScanning,
                                    onScan = ::requestScan,
                                    onUseInstance = {
                                        baseUrl = it.url
                                        oauthSession = null
                                        errorMessage = null
                                    }
                                )
                                SetupStep.Token -> TokenStep(
                                    token = token,
                                    showToken = showToken,
                                    onAccountLogin = {
                                        val normalizedBaseUrl = normalizeHomeAssistantUrl(baseUrl)
                                        if (normalizedBaseUrl.isBlank() || normalizedBaseUrl.equals("demo", ignoreCase = true)) {
                                            errorMessage = "Enter your Home Assistant URL before logging in."
                                        } else {
                                            errorMessage = null
                                            showAccountLogin = true
                                        }
                                    },
                                    onTokenChange = {
                                        token = it
                                        oauthSession = null
                                        errorMessage = null
                                    },
                                    onToggleToken = { showToken = !showToken }
                                )
                                SetupStep.Cameras -> CamerasStep(
                                    frigateUrl = frigateUrl,
                                    onFrigateUrlChange = {
                                        frigateUrl = it
                                        errorMessage = null
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(140.dp))
                    }

                    val isFinalStep = stepIndex == steps.lastIndex
                    val canContinue = when (currentStep) {
                        SetupStep.Instance -> normalizeHomeAssistantUrl(baseUrl).isNotBlank()
                        SetupStep.Token -> token.isNotBlank()
                        SetupStep.Cameras -> true
                    }

                    SetupSaveDock(
                        stepIndex = stepIndex,
                        isSaving = isSaving,
                        canContinue = canContinue,
                        isFinalStep = isFinalStep,
                        errorMessage = errorMessage,
                        onBack = {
                            errorMessage = null
                            stepIndex--
                        },
                        onNext = {
                            if (isFinalStep) {
                                saveSetup()
                            } else if (canContinue) {
                                errorMessage = null
                                stepIndex++
                            }
                        },
                        canDismiss = canDismiss,
                        onDismiss = onDismiss,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }

            if (showAccountLogin) {
                HomeAssistantAccountLoginDialog(
                    baseUrl = normalizeHomeAssistantUrl(baseUrl),
                    onConnected = { session ->
                        token = session.accessToken
                        oauthSession = session
                        showToken = false
                        errorMessage = null
                    },
                    onDismiss = { showAccountLogin = false },
                    onError = { errorMessage = it }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupSaveDock(
    stepIndex: Int,
    isSaving: Boolean,
    canContinue: Boolean,
    isFinalStep: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onNext: () -> Unit,
    canDismiss: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val dockGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to scheme.surface.copy(alpha = 0f),
            0.42f to scheme.surface.copy(alpha = 0.58f),
            1f to scheme.surface.copy(alpha = 0.96f)
        )
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        val hasBack = stepIndex > 0
        val buttonWidth = if (hasBack) {
            (maxWidth - 12.dp - 32.dp) / 2
        } else {
            maxWidth - 32.dp
        }

        Spacer(
            modifier = Modifier
                .matchParentSize()
                .background(dockGradient)
                
        )
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .background(dockGradient)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 12.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = scheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasBack) {
                    MotionButton(
                        text = "Back",
                        icon = "arrow_back",
                        enabled = !isSaving,
                        onClick = onBack,
                        width = buttonWidth,
                        height = 64.dp,
                        fontSize = 20.sp,
                        iconSize = 22.dp,
                        defaultState = MotionButtonState(
                            backgroundColor = if (!isSaving) scheme.surfaceVariant else scheme.surfaceVariant.copy(alpha = 0.38f),
                            contentColor = if (!isSaving) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.38f),
                            outlineWidth = 0.dp,
                            outlineColor = scheme.outline
                        )
                    )
                }

                val buttonText = if (isSaving) "Saving..." else if (isFinalStep) "Save setup" else "Next"
                val buttonIcon = if (isSaving) "" else if (isFinalStep) "check" else "arrow_forward"

                val nextEnabled = !isSaving && canContinue

                MotionButton(
                    text = buttonText,
                    icon = buttonIcon,
                    enabled = nextEnabled,
                    onClick = {
                        if (nextEnabled) onNext()
                    },
                    width = buttonWidth,
                    height = 64.dp,
                    fontSize = 20.sp,
                    iconSize = 22.dp,
                    defaultState = MotionButtonState(
                        backgroundColor = if (nextEnabled) scheme.primary else scheme.surfaceVariant,
                        contentColor = if (nextEnabled) scheme.onPrimary else scheme.onSurfaceVariant,
                        outlineWidth = 0.dp,
                        outlineColor = scheme.outline
                    )
                )
            }

            if (canDismiss) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(steps: List<SetupStep>, activeIndex: Int) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEachIndexed { index, step ->
            val active = index <= activeIndex
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) colorScheme.primary else colorScheme.outlineVariant)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun InstanceStep(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    discovered: List<DiscoveredHomeAssistantInstance>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onUseInstance: (DiscoveredHomeAssistantInstance) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        SetupTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = "Home Assistant URL",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            )
        )

        Spacer(Modifier.height(12.dp))

        val scanEnabled = !isScanning
        val scanIcon = if (isScanning) "sync" else "travel_explore"
        val scanPainter = rememberRotatingSymbolPainter(scanIcon, isRotating = isScanning)

        MotionButton(
            text = if (isScanning) "Searching network..." else "Search local network",
            icon = scanPainter,
            enabled = scanEnabled,
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            fontSize = 20.sp,
            iconSize = 22.dp,
            defaultState = MotionButtonState(
                backgroundColor = Color.Transparent,
                contentColor = if (scanEnabled) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                outlineWidth = 1.5.dp,
                outlineColor = if (scanEnabled) colorScheme.outline else colorScheme.outline.copy(alpha = 0.38f)
            )
        )

        if (isScanning && discovered.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Scanning network for instances...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        if (discovered.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Found on this network",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                discovered.forEach { instance ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 66.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .clickable { onUseInstance(instance) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = rememberSymbolPainter("home_assistant"),
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = instance.name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = instance.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Icon(
                            painter = rememberSymbolPainter("chevron_right"),
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenStep(
    token: String,
    showToken: Boolean,
    onAccountLogin: () -> Unit,
    onTokenChange: (String) -> Unit,
    onToggleToken: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        MotionButton(
            text = "Log in with Home Assistant",
            icon = "login",
            onClick = onAccountLogin,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            fontSize = 20.sp,
            iconSize = 22.dp,
            defaultState = MotionButtonState(
                backgroundColor = colorScheme.primary,
                contentColor = colorScheme.onPrimary,
                outlineWidth = 0.dp,
                outlineColor = colorScheme.outline
            )
        )

        Spacer(Modifier.height(18.dp))

        Text(
            "Or paste a long-lived access token manually.",
            style = MaterialTheme.typography.titleSmall,
            color = colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TokenInstruction(index = 1, text = "Open Home Assistant in a browser.")
            TokenInstruction(index = 2, text = "Open your user profile from the sidebar.")
            TokenInstruction(index = 3, text = "Find Long-lived access tokens and create one.")
            TokenInstruction(index = 4, text = "Paste the generated token below.")
        }

        Spacer(Modifier.height(16.dp))

        SetupTextField(
            value = token,
            onValueChange = onTokenChange,
            label = "Long-lived access token",
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleToken) {
                    Icon(
                        painter = rememberSymbolPainter(if (showToken) "visibility_off" else "visibility"),
                        contentDescription = if (showToken) "Hide token" else "Show token",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Account login stores Home Assistant OAuth tokens locally and refreshes access automatically. Manual long-lived tokens still work.",
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun TokenInstruction(index: Int, text: String) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CamerasStep(
    frigateUrl: String,
    onFrigateUrlChange: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        SetupTextField(
            value = frigateUrl,
            onValueChange = onFrigateUrlChange,
            label = "Frigate URL (optional)",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            )
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Leave this empty if you do not use Frigate cameras.",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun SetupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 64.dp
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, Modifier.padding(start = 2.dp)) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 4,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}

private fun setupErrorMessage(error: Throwable): String {
    if (error is HttpException) {
        return when (error.code()) {
            401 -> "Home Assistant rejected the login token."
            404 -> "Could not find the Home Assistant API at this URL."
            else -> "Home Assistant returned ${error.code()}."
        }
    }
    return error.localizedMessage ?: "Could not connect to Home Assistant."
}
