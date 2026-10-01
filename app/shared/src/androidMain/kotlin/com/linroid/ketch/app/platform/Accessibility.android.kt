package com.linroid.ketch.app.platform

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberReduceMotion(): Boolean {
  val resolver = LocalContext.current.contentResolver
  var reduce by remember(resolver) { mutableStateOf(animationsOff(resolver)) }
  DisposableEffect(resolver) {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        reduce = animationsOff(resolver)
      }
    }
    val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
    resolver.registerContentObserver(uri, false, observer)
    onDispose { resolver.unregisterContentObserver(observer) }
  }
  return reduce
}

private fun animationsOff(resolver: ContentResolver): Boolean =
  Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
