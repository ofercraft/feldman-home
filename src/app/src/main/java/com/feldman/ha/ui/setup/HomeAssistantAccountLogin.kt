package com.feldman.ha.ui.setup

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.api.HomeAssistantOAuthSession
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.launch
import java.util.UUID

private const val LoginCallbackPath = "auth/feldman_home_callback"

private data class HomeAssistantAuthorizeRequest(
    val authorizeUrl: String,
    val clientId: String,
    val redirectUri: String,
    val state: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HomeAssistantAccountLoginDialog(
    baseUrl: String,
    onConnected: (HomeAssistantOAuthSession) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val request = remember(baseUrl) { buildHomeAssistantAuthorizeRequest(baseUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isFinishingLogin by remember { mutableStateOf(false) }

    fun handleAuthRedirect(uri: Uri): Boolean {
        if (!uri.matchesRedirectUri(request.redirectUri)) return false

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            onError(uri.getQueryParameter("error_description") ?: error)
            onDismiss()
            return true
        }

        if (uri.getQueryParameter("state") != request.state) {
            onError("Home Assistant returned an unexpected login response.")
            onDismiss()
            return true
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            onError("Home Assistant did not return a login code.")
            onDismiss()
            return true
        }

        isFinishingLogin = true
        scope.launch {
            runCatching {
                HomeAssistantAuth.exchangeAuthorizationCode(
                    rootUrl = homeAssistantRootUrl(baseUrl),
                    clientId = request.clientId,
                    code = code
                )
            }.fold(
                onSuccess = { session ->
                    onConnected(session)
                    onDismiss()
                },
                onFailure = { error ->
                    onError(accountLoginErrorMessage(error))
                    onDismiss()
                }
            )
        }
        return true
    }

    BackHandler(onBack = onDismiss)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.surface
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colorScheme.surfaceVariant,
                        contentColor = colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        painter = rememberSymbolPainter("close"),
                        contentDescription = "Close",
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = "Log in with Home Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean = handleAuthRedirect(request.url)

                                @Deprecated("Deprecated in Android WebViewClient")
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                                    handleAuthRedirect(Uri.parse(url))
                            }
                            loadUrl(request.authorizeUrl)
                        }.also { webView = it }
                    }
                )

                if (isFinishingLogin) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.surface.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Finishing login...",
                                color = colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(context) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

private fun buildHomeAssistantAuthorizeRequest(baseUrl: String): HomeAssistantAuthorizeRequest {
    val rootUrl = homeAssistantRootUrl(baseUrl)
    val clientId = rootUrl
    val redirectUri = rootUrl + LoginCallbackPath
    val state = UUID.randomUUID().toString()
    val authorizeUrl = Uri.parse(rootUrl + "auth/authorize")
        .buildUpon()
        .appendQueryParameter("client_id", clientId)
        .appendQueryParameter("redirect_uri", redirectUri)
        .appendQueryParameter("state", state)
        .build()
        .toString()

    return HomeAssistantAuthorizeRequest(
        authorizeUrl = authorizeUrl,
        clientId = clientId,
        redirectUri = redirectUri,
        state = state
    )
}

private fun homeAssistantRootUrl(baseUrl: String): String {
    val url = HomeAssistantAuth.normalizeHomeAssistantRootUrl(baseUrl)
    if (url.isBlank() || url.equals("demo", ignoreCase = true)) {
        throw IllegalArgumentException("Enter your Home Assistant URL before using account login.")
    }
    return url
}

private fun Uri.matchesRedirectUri(redirectUri: String): Boolean {
    val expected = Uri.parse(redirectUri)
    return scheme == expected.scheme &&
        host == expected.host &&
        port == expected.port &&
        path == expected.path
}

private fun accountLoginErrorMessage(error: Throwable): String =
    error.localizedMessage ?: "Could not finish Home Assistant login."
