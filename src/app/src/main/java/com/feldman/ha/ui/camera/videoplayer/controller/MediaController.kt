package com.feldman.ha.ui.camera.videoplayer.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.feldman.ha.ui.camera.videoplayer.service.MediaPlaybackService

@OptIn(UnstableApi::class)
@Composable
fun rememberMediaController(context: Context): MediaController? {
    var controller by remember { mutableStateOf<MediaController?>(null) }

    LaunchedEffect(Unit) {
        context.startService(Intent(context, MediaPlaybackService::class.java))

        val token = SessionToken(
            context,
            ComponentName(context, MediaPlaybackService::class.java)
        )

        val future = MediaController
            .Builder(context, token)
            .buildAsync()

        future.addListener(
            {
                controller = future.get()
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            controller?.release()
            controller = null
        }
    }

    return controller
}
