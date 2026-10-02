package com.linroid.ketch.app.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun SystemAppearance(dark: Boolean?) {
  val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return
  val system = isSystemInDarkTheme()
  val night = dark ?: system
  DisposableEffect(activity, night) {
    // The bars enableEdgeToEdge draws by default, with icons for the app's appearance rather
    // than the system's.
    activity.enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { night },
      navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { night },
    )
    onDispose {}
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

// The scrims enableEdgeToEdge puts behind three-button navigation.
private val LightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
