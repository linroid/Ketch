package com.linroid.kdown.app.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.kdown.app.App

class MainActivity : ComponentActivity() {

  private var service: KDownService? by mutableStateOf(null)
  private val requestNotificationPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { }
  private val requestExternalStoragePermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { }
  private val requestNearbyWifiPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { }

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      service = (binder as KDownService.LocalBinder).service
    }

    override fun onServiceDisconnected(name: ComponentName) {
      service = null
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    requestExternalStoragePermissionIfNeeded()
    requestNotificationPermissionIfNeeded()
    requestNearbyWifiPermissionIfNeeded()
    bindService(
      Intent(this, KDownService::class.java),
      connection,
      BIND_AUTO_CREATE,
    )
    setContent {
      val svc = service
      if (svc != null) {
        App(svc.instanceManager)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    unbindService(connection)
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return
    }
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
      return
    }
    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun requestNearbyWifiPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return
    }
    if (checkSelfPermission(
        Manifest.permission.NEARBY_WIFI_DEVICES
      ) == PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    requestNearbyWifiPermission.launch(
      Manifest.permission.NEARBY_WIFI_DEVICES
    )
  }

  private fun requestExternalStoragePermissionIfNeeded() {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
      return
    }
    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
      return
    }
    requestExternalStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
  }
}
