package com.feldman.ha.ui.camera.videoplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.feldman.ha.MainActivity
import okhttp3.OkHttpClient
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.feldman.ha.api.HomeAssistantAuth
import kotlinx.coroutines.runBlocking

@UnstableApi
class MediaPlaybackService : MediaSessionService() {

    private lateinit var basePlayer: ExoPlayer
    lateinit var player: Player
    lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        
        val client = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = runBlocking { HomeAssistantAuth.currentAccessToken(applicationContext) }
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                chain.proceed(request)
            }
            .build()
            
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            this,
            OkHttpDataSource.Factory(client)
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        this.basePlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000L)
            .setSeekForwardIncrementMs(10000L)
            .build()

        player = object : ForwardingPlayer(basePlayer) {
            override fun getAvailableCommands(): androidx.media3.common.Player.Commands {
                return androidx.media3.common.Player.Commands.Builder()
                    .addAllCommands()
                    .build()
            }

            override fun isCurrentMediaItemSeekable(): Boolean {
                return true
            }

            override fun isCurrentMediaItemLive(): Boolean {
                return false // Lie and say it's not live to encourage seeking
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return true
            }
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                Log.d("MediaPlaybackService", "onConnect: controller=${controller.packageName}")
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .build()
                val playerCommands = Player.Commands.Builder()
                    .addAllCommands()
                    .build()
                
                Log.d("MediaPlaybackService", "Granting ALL commands to controller")
                
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }
            
            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                return SessionResult.RESULT_SUCCESS
            }
        }

        session = MediaSession.Builder(this, player)
            .setId("home-video-session")
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(sessionCallback)
            .build()

        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
    }

    private fun androidx.media3.common.Player.Commands.asListOf(): List<Int> {
        val list = mutableListOf<Int>()
        for (i in 0 until 50) { // Check first 50 commands
            if (contains(i)) list.add(i)
        }
        return list
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return session
    }

    override fun onDestroy() {
        session.release()
        player.release()
        super.onDestroy()
    }
}
