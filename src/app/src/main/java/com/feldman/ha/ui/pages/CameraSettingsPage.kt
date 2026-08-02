package com.feldman.ha.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feldman.ha.api.HomeAssistantAuth
import com.feldman.ha.api.HomeAssistantOAuthSession
import com.feldman.ha.ui.setup.normalizeOptionalNetworkUrl
import com.feldman.motion.SettingsScaffold
import androidx.compose.ui.platform.LocalContext

/**
 * The Frigate NVR endpoint. Separate from the Home Assistant connection because it is a different
 * service with its own address, and leaving it blank simply hides the cameras tab's contents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsPage(
    baseUrl: String,
    token: String,
    initialFrigateUrl: String,
    onBack: () -> Unit,
    onSave: (baseUrl: String, token: String, frigateUrl: String, oauthSession: HomeAssistantOAuthSession?) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var frigateUrl by remember(initialFrigateUrl) { mutableStateOf(initialFrigateUrl) }

    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(
            title = "Cameras",
            scaffoldModifier = Modifier.fillMaxSize(),
            topBar = { SettingsTopBar("Cameras", onBack) }
        ) {
            title("Frigate")
            section {
                item(padding = 16.dp) {
                    SettingsTextField(
                        value = frigateUrl,
                        onValueChange = { frigateUrl = it },
                        label = "Frigate URL",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        )
                    )
                }
                item(padding = 16.dp) {
                    Text(
                        text = "Point this at your Frigate instance to browse its cameras and recordings. " +
                            "Home Assistant cameras work without it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            item { Spacer(Modifier.height(128.dp)) }
        }

        SettingsSaveDock(
            onSave = {
                view.clearFocus()
                // Carries the connection values through untouched — onSave takes the whole set.
                onSave(
                    baseUrl,
                    token,
                    normalizeOptionalNetworkUrl(frigateUrl),
                    HomeAssistantAuth.storedOAuthSession(context)
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
