package com.linroid.ketch.app.ui.connect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal actual fun rememberNearbyAccess(): NearbyAccess {
  val context = LocalContext.current
  // Runs once the system answers, whatever the answer: the search may work without it.
  var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    pending?.invoke()
    pending = null
  }
  return remember(context, launcher) {
    NearbyAccess { onDone ->
      val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
        PackageManager.PERMISSION_GRANTED
      if (granted) {
        onDone()
      } else {
        pending = onDone
        launcher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
      }
    }
  }
}
