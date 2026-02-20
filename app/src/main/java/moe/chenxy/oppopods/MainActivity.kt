package moe.chenxy.oppopods

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import moe.chenxy.oppopods.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val prefs = remember { getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE) }
            val themeMode = remember { mutableStateOf(prefs.getInt("theme_mode", 0)) }
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    prefs.edit().putInt("theme_mode", it).apply()
                }
            )
        }
    }
}
