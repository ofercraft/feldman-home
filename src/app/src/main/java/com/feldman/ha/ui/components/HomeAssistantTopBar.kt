package com.feldman.ha.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feldman.ha.data.HAEntity
import com.feldman.ha.R


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAssistantTopBar(
    onShowCameras: () -> Unit,
    onShowSettings: () -> Unit,
    isCameraOpen: Boolean,
    edit: Boolean = false,
    editProgress: Float = if (edit) 1f else 0f,
    containerColor: Color? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val expressiveCanvas = com.feldman.ha.ui.cards.ExpressiveCanvasSetting.isEnabled(androidx.compose.ui.platform.LocalContext.current)
    val modeProgress = editProgress.coerceIn(0f, 1f)
    val topBarContainerColor = containerColor ?: if (expressiveCanvas) {
        Color.Transparent
    } else {
        colorScheme.surfaceContainerHigh
    }
    val titleColor = lerp(
        if (expressiveCanvas) colorScheme.primary else colorScheme.onSurface,
        colorScheme.tertiary,
        modeProgress
    )
    val actionContainerColor = lerp(colorScheme.primary, colorScheme.tertiary, modeProgress)
    val actionContentColor = lerp(colorScheme.onPrimary, colorScheme.onTertiary, modeProgress)
    
    TopAppBar(
        title = {
//            Row(verticalAlignment = Alignment.CenterVertically) {
//                Image(
//                    painter = painterResource(id = R.drawable.ic_icon),
//                    contentDescription = "Logo",
//                    modifier = Modifier.size(26.dp),
//                    colorFilter = ColorFilter.tint(colorScheme.primary)
//                )
//                Spacer(Modifier.width(12.dp))
//                Text(
//                    text = "Feldman Home",
//                    color = colorScheme.onSurface,
//                    fontFamily = feldmanFont(width = 120f, weight = 700)
//                )
//            }
            AppPageTitle("Feldman Home", color = titleColor)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = topBarContainerColor,
            titleContentColor = titleColor,
            actionIconContentColor = actionContentColor
        ),
        actions = {
            FilledIconButton(
                onClick = onShowSettings,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = actionContainerColor,
                    contentColor = actionContentColor
                )
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = "Settings"
                )
            }
        }
    )
}
