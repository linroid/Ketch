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
    NearbyAccess { search, onDone ->
      val permission = localNetworkPermission(search)
      val granted = permission == null ||
        ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
      if (granted) {
        onDone()
      } else {
        pending = onDone
        launcher.launch(permission)
      }
    }
  }
}

/**
 * The permission this Android version needs for the local network: `ACCESS_LOCAL_NETWORK` from
 * Android 17, `NEARBY_WIFI_DEVICES` on 13 to 16 when it [search]es (mDNS), else `null`.
 */
private fun localNetworkPermission(search: Boolean): String? = when {
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN ->
    Manifest.permission.ACCESS_LOCAL_NETWORK
  search && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
    Manifest.permission.NEARBY_WIFI_DEVICES
  else -> null
}
