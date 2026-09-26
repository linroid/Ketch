package com.linroid.ketch.app.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.linroid.ketch.app.App
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {

  private var service: KetchService? by mutableStateOf(null)
  private val incoming = IncomingDownloads()
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
      service = (binder as KetchService.LocalBinder).service
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
      Intent(this, KetchService::class.java),
      connection,
      BIND_AUTO_CREATE,
    )
    // A recreated activity still carries the intent it already handled.
    if (savedInstanceState == null) {
      handleIntent(intent)
    }
    setContent {
      val svc = service
      if (svc != null) {
        App(svc.instanceManager, svc.aiProviderFactory, incoming)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  /** Reads a `.torrent` file opened with Ketch from a file manager or another app. */
  private fun handleIntent(intent: Intent?) {
    if (intent?.action != Intent.ACTION_VIEW) return
    val uri = intent.data ?: return
    lifecycleScope.launch(Dispatchers.IO) {
      val name = displayName(uri)
      try {
        incoming.offerTorrentFile(name, readAtMost(uri, MAX_TORRENT_FILE_BYTES + 1))
      } catch (e: Exception) {
        incoming.offerUnreadable(name, e)
      }
    }
  }

  private fun displayName(uri: Uri): String {
    val queried = try {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
      // Not every provider answers queries, e.g. file: URIs.
      null
    }
    return queried ?: uri.lastPathSegment ?: "torrent"
  }

  /** Reads up to [limit] bytes; a longer file is cut off there and rejected by its size. */
  private fun readAtMost(uri: Uri, limit: Int): ByteArray {
    val input = contentResolver.openInputStream(uri)
      ?: throw IOException("The file could not be opened")
    return input.use {
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (output.size() < limit) {
        val read = it.read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
      }
      output.toByteArray()
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
