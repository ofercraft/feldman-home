package com.feldman.ha.ui.camera

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.media3.common.PlaybackException
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(UnstableApi::class)
@Composable
fun LegacyVideoPlayer(
    url: String,
    token: String,
    modifier: Modifier = Modifier,
    useHls: Boolean = false,
    autoPlay: Boolean = true
) {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    
    val exoPlayer = remember(token) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                chain.proceed(request)
            }
            .build()
        
        val dataSourceFactory = OkHttpDataSource.Factory(client)
        
        val mediaSourceFactory = if (useHls) {
            HlsMediaSource.Factory(dataSourceFactory)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("VideoPlayer", "Playback error: ${error.message}", error)
                        // Retry after a delay if the player is not released
                        scope.launch {
                            delay(2000)
                            // Check if the player is still active and in an error state
                            if (playbackState == Player.STATE_IDLE && playerError != null) {
                                prepare()
                                play()
                            }
                        }
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = autoPlay
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }

    // Release the player when it's replaced or removed
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Update URL without recreating the player if the token hasn't changed
    DisposableEffect(url) {
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        onDispose { }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = {
            it.player = exoPlayer
        }
    )
}
