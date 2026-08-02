package com.feldman.ha.ui.standby

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.feldman.motion.AppTheme

class HomeStandbyPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = when (HomeStandbyPreferences.orientation(this)) {
            HomeStandbyOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            HomeStandbyOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            HomeStandbyOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            AppTheme {
                HomeStandbyHost(
                    onDismiss = ::finish,
                    isInteractive = true,
                    allowEdit = true
                )
            }
        }
    }
}
