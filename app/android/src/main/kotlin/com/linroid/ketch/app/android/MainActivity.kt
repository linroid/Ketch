package com.linroid.ketch.app.android

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.linroid.ketch.app.App
import com.linroid.ketch.app.instance.InstanceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

class MainActivity : ComponentActivity() {

  private var service: KetchService? by mutableStateOf(null)
  private val ketchApplication get() = application as KetchApplication
  private val requestNotificationPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { }
  private val requestExternalStoragePermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { }
  private val permissionPrefs by lazy { getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE) }
  private var notificationOffer: Job? = null
  private var notificationRationale: AlertDialog? = null

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      val connected = (binder as KetchService.LocalBinder).service
      service = connected
      offerNotificationsAfterFirstAdd(connected.instanceManager)
    }

    override fun onServiceDisconnected(name: ComponentName) {
      service = null
      notificationOffer?.cancel()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    requestExternalStoragePermissionIfNeeded()
    bindService(
      Intent(this, KetchService::class.java),
      connection,
      BIND_AUTO_CREATE,
    )
    // Skip intents already handled: a recreated activity carries its old intent (the file is
    // still pending in the application), and reopening from Recents replays the task's launch
    // intent, whose one-time read grant may have expired.
    val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
    if (savedInstanceState == null && !launchedFromHistory) {
      handleIntent(intent)
    }
    setContent {
      val svc = service
      if (svc != null) {
        App(
          svc.instanceManager,
          svc.aiProviderFactory,
          incoming = ketchApplication.incoming,
          fileLogger = ketchApplication.fileLogger,
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  /** Hands a `.torrent` file opened with Ketch to the app, which keeps it until handled. */
  private fun handleIntent(intent: Intent?) {
    if (intent?.action != Intent.ACTION_VIEW) return
    val uri = intent.data ?: return
    ketchApplication.openFile(uri)
  }

  override fun onDestroy() {
    super.onDestroy()
    // Dismissed without an answer, the offer is made again after a later add.
    notificationRationale?.dismiss()
    unbindService(connection)
  }

  /**
   * Offers notifications once, when the first task is added to this device, instead of at
   * launch. Tasks restored from the previous run do not count.
   */
  private fun offerNotificationsAfterFirstAdd(manager: InstanceManager) {
    if (!shouldOfferNotifications()) return
    val tasks = manager.embedded?.tasks ?: return
    val since = Clock.System.now()
    notificationOffer?.cancel()
    notificationOffer = lifecycleScope.launch {
      tasks.first { list -> list.any { it.createdAt >= since } }
      withStarted { showNotificationRationale() }
    }
  }

  private fun shouldOfferNotifications(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED &&
      !permissionPrefs.getBoolean(KEY_NOTIFICATIONS_OFFERED, false)

  private fun showNotificationRationale() {
    if (!shouldOfferNotifications()) return
    val answered = { permissionPrefs.edit { putBoolean(KEY_NOTIFICATIONS_OFFERED, true) } }
    notificationRationale = AlertDialog.Builder(this, dialogTheme())
      .setTitle("Get notified when downloads finish")
      .setMessage("Ketch can tell you when a download finishes or fails while you use other apps.")
      .setPositiveButton("Allow") { _, _ ->
        answered()
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
      .setNegativeButton("Not now") { _, _ -> answered() }
      .setOnCancelListener { answered() }
      .show()
  }

  private fun dialogTheme(): Int {
    val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
      android.R.style.Theme_DeviceDefault_Dialog_Alert
    } else {
      android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }
  }

  private fun requestExternalStoragePermissionIfNeeded() {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
      return
    }
    val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
    if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
      return
    }
    requestExternalStoragePermission.launch(permission)
  }

  private companion object {
    const val PERMISSION_PREFS = "permissions"
    const val KEY_NOTIFICATIONS_OFFERED = "notifications_offered"
  }
}
