package com.linroid.ketch.app.android

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentResolver
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
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.linroid.ketch.app.App
import com.linroid.ketch.app.feedback.AndroidNotifier
import com.linroid.ketch.app.feedback.NotificationLink
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LinkSource
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.rememberAppController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {

  private var service: KetchService? by mutableStateOf(null)
  private var notificationLink: NotificationLink? by mutableStateOf(null)
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
      connected.setInFront(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
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
        val controller = rememberAppController(
          instanceManager = svc.instanceManager,
          aiProviderFactory = svc.aiProviderFactory,
          incoming = ketchApplication.incoming,
          speedMode = svc.speedMode,
        )
        val link = notificationLink
        LaunchedEffect(controller, link) {
          if (link == null) return@LaunchedEffect
          controller.state.open(link)
          notificationLink = null
        }
        App(
          controller,
          activityEvents = svc.activityEvents,
          fileLogger = ketchApplication.fileLogger,
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  // The service's events show as toasts only while the activity is resumed.
  override fun onResume() {
    super.onResume()
    service?.setInFront(true)
  }

  override fun onPause() {
    service?.setInFront(false)
    super.onPause()
  }

  /**
   * Shows what a tapped notification is about, or hands what Ketch was opened with to the app,
   * which keeps it until handled: a `.torrent` file, a `magnet:` link or a `ketch://pair` link.
   */
  private fun handleIntent(intent: Intent?) {
    val link = AndroidNotifier.linkOf(intent)
    if (link != null) {
      // Buttons do not dismiss their notification.
      if (link.retry) link.taskKey?.let { AndroidNotifier.cancel(this, it) }
      notificationLink = link
      return
    }
    if (intent?.action != Intent.ACTION_VIEW) return
    val uri = intent.data ?: return
    when (uri.scheme?.lowercase()) {
      ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE -> ketchApplication.openFile(uri)
      else -> ketchApplication.incoming.offerLink(uri.toString(), LinkSource.OpenUrl)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    // Dismissed without an answer, the offer shows again in the recreated activity.
    notificationRationale?.dismiss()
    unbindService(connection)
  }

  /**
   * Offers notifications once, when the first task is added to this device, instead of at
   * launch. Tasks restored from the previous run do not count. The offer stays due until it is
   * answered, so a rotation or a restart while it shows brings it back.
   */
  private fun offerNotificationsAfterFirstAdd(manager: InstanceManager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !shouldOfferNotifications()) {
      return
    }
    val tasks = manager.embedded?.tasks ?: return
    val since = Clock.System.now()
    notificationOffer?.cancel()
    notificationOffer = lifecycleScope.launch {
      if (!permissionPrefs.getBoolean(KEY_NOTIFICATIONS_DUE, false)) {
        tasks.first { list -> list.any { it.createdAt >= since } }
        permissionPrefs.edit { putBoolean(KEY_NOTIFICATIONS_DUE, true) }
      }
      withStarted { showNotificationRationale() }
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun shouldOfferNotifications(): Boolean {
    val permission = Manifest.permission.POST_NOTIFICATIONS
    return checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
      !permissionPrefs.getBoolean(KEY_NOTIFICATIONS_OFFERED, false)
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun showNotificationRationale() {
    if (!shouldOfferNotifications()) return
    val answered = {
      permissionPrefs.edit {
        putBoolean(KEY_NOTIFICATIONS_OFFERED, true)
        remove(KEY_NOTIFICATIONS_DUE)
      }
    }
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
    const val KEY_NOTIFICATIONS_DUE = "notifications_due"
  }
}

// A tap can start the app before its device has loaded the task.
private val TASK_LOAD_TIMEOUT = 10.seconds

/**
 * Shows what a notification tap asks for: the device it is about, the status tab or the task,
 * which Retry also retries.
 */
private suspend fun AppState.open(link: NotificationLink) {
  val key = link.taskKey
  val entry = instances.value.firstOrNull { it.deviceId == (key?.deviceId ?: LOCAL_DEVICE_ID) }
    ?: return
  if (entry != activeInstance.value) switchInstance(entry)
  link.filter?.let { statusFilter = it }
  if (key == null) return
  val task = withTimeoutOrNull(TASK_LOAD_TIMEOUT) {
    entry.instance.tasks.mapNotNull { tasks -> tasks.firstOrNull { it.taskId == key.taskId } }
      .first()
  } ?: return
  if (!statusFilter.matches(task.state.value)) statusFilter = StatusFilter.All
  inspect(key)
  if (link.retry) retry(task)
}
