package moe.chenxy.oppopods

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import moe.chenxy.oppopods.ui.App

class MainActivity : ComponentActivity() {
    private val showPopup = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showPopup.value = intent?.getBooleanExtra("show_popup", false) == true

        setContent {
            val darkMode = isSystemInDarkTheme()

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false

                onDispose {}
            }

            App(showPopup = showPopup, onFinish = { finish() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("show_popup", false)) {
            showPopup.value = true
        }
    }
}
