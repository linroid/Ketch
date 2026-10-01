package com.linroid.ketch.app.android

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.net.ConnectivityManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.app.state.ForegroundPolicy
import com.linroid.ketch.app.state.ForegroundStatus
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@SuppressLint("InlinedApi")
class KetchService : Service() {
  private val log = KetchLogger("KetchService")

  inner class LocalBinder : Binder() {
    val service: KetchService get() = this@KetchService
  }

  lateinit var instanceManager: InstanceManager
    private set
  /** Builds discovery providers from the settings the user saves. */
  val aiProviderFactory: AiDiscoveryProviderFactory =
    EmbeddedAiDiscoveryProviderFactory()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val binder = LocalBinder()
  private var isForeground = false
  private var isBound = false
  private var latestStatus = ForegroundStatus()

  override fun onCreate() {
    super.onCreate()
    // Call startForeground() immediately to avoid ANR from
    // startForegroundService() timeout. The monitor updates it later.
    createNotificationChannel()
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(latestStatus),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
    isForeground = true

    val configStore = FileConfigStore(
      filesDir.resolve("config.toml").absolutePath,
    )
    val config = configStore.load()
    val taskStore = createSqliteTaskStore(DriverFactory(this))
    val instanceName = config.name
      ?: android.os.Build.MODEL
    val torrentSource = TorrentDownloadSource(
      TorrentConfig(
        stateDirectory = filesDir.resolve("torrent-state").absolutePath,
        additionalTrackers = config.torrent.trackers,
      ),
    )
    instanceManager = InstanceManager(
      factory = InstanceFactory(
        deviceName = instanceName,
        embeddedFactory = {
          Ketch(
            httpEngine = KtorHttpEngine.withNetworkInterfaces(
              getSystemService(ConnectivityManager::class.java)
            ),
            taskStore = taskStore,
            config = config.download,
            name = instanceName,
            logger = Logger.combine(
              Logger.console(LogLevel.DEBUG),
              (application as KetchApplication).fileLogger
            ),
            additionalSources = listOf(FtpDownloadSource(), torrentSource),
          )
        },
        localServerFactory = { ketchApi ->
          // Reloaded here so a restart from Settings picks up the
          // saved port, token and mDNS choice.
          val saved = configStore.load()
          val serverConfig = saved.server
          log.i { "Starting local server on port ${serverConfig.port}" }
          val server = KetchServer(
            ketchApi,
            host = serverConfig.host,
            port = serverConfig.port,
            apiToken = serverConfig.apiToken,
            name = saved.name ?: instanceName,
            // With a token, pages on any site, such as the web app, may call the API, as
            // they still need the token. Without one, KetchServer refuses them.
            corsAllowedHosts = serverConfig.corsAllowedHosts.ifEmpty {
              if (serverConfig.apiToken == null) emptyList() else listOf("*")
            },
            allowedHosts = serverConfig.allowedHosts,
            mdnsEnabled = serverConfig.mdnsEnabled,
          )
          server.start(wait = false)
          log.i { "Local server started on port ${serverConfig.port}" }
          object : LocalServerHandle {
            override fun stop() {
              log.i { "Stopping local server" }
              server.stop()
              log.i { "Local server stopped" }
            }
          }
        },
        applyTorrentSettings = { torrentSource.setAdditionalTrackers(it.trackers) },
      ),
      initialRemotes = config.remotes,
      configStore = configStore,
    )

    startForegroundMonitor()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_REPOST_NOTIFICATION && isForeground && latestStatus.isRequired) {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        buildNotification(latestStatus),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder {
    isBound = true
    return binder
  }

  override fun onUnbind(intent: Intent?): Boolean {
    isBound = false
    if (!isForeground) {
      stopSelf()
    }
    return false
  }

  override fun onDestroy() {
    super.onDestroy()
    instanceManager.close()
    scope.cancel()
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Ketch Service",
      NotificationManager.IMPORTANCE_LOW,
    )
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
  }

  /**
   * Keeps the service in the foreground while the embedded device downloads or shares itself,
   * whichever device the app shows.
   */
  private fun startForegroundMonitor() {
    val embedded = checkNotNull(instanceManager.embedded) { "No embedded instance" }
    scope.launch {
      ForegroundPolicy.observe(embedded.tasks, instanceManager.serverState)
        .flowOn(Dispatchers.Default)
        .collect { status ->
          latestStatus = status
          if (status.isRequired) {
            if (!isForeground) {
              log.i {
                "Entering the foreground: downloading=${status.downloading} " +
                  "queued=${status.queued} server=${status.serverState is ServerState.Running}"
              }
            }
            enterForeground(status)
          } else if (isForeground) {
            log.i { "Leaving the foreground" }
            ServiceCompat.stopForeground(
              this@KetchService,
              ServiceCompat.STOP_FOREGROUND_REMOVE,
            )
            isForeground = false
            if (!isBound) stopSelf()
          }
        }
    }
  }

  private fun enterForeground(status: ForegroundStatus) {
    try {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        buildNotification(status),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
      isForeground = true
    } catch (e: IllegalStateException) {
      // Android 12+ refuses to start a foreground service while the app is in the background,
      // e.g. when a scheduled download starts after the service left the foreground.
      log.w { "Could not enter the foreground: ${e.describeCauses()}" }
    }
  }

  private fun buildNotification(status: ForegroundStatus): android.app.Notification {
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val text = listOfNotNull(
      status.downloading.takeIf { it > 0 }?.let { "Downloading $it ${files(it)}" },
      status.queued.takeIf { it > 0 }?.let { "$it waiting" },
      (status.serverState as? ServerState.Running)?.let { "Server on :${it.port}" },
    ).joinToString(" · ")
    val deleteIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, KetchService::class.java).setAction(ACTION_REPOST_NOTIFICATION),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("Ketch")
      .setContentText(text)
      .setContentIntent(contentIntent)
      .setDeleteIntent(deleteIntent)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setAutoCancel(false)
      .setSilent(true)
      .build()
    notification.flags = notification.flags or android.app.Notification.FLAG_NO_CLEAR
    return notification
  }

  private fun files(count: Int): String = if (count == 1) "file" else "files"

  companion object {
    private const val CHANNEL_ID = "ketch_service"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_REPOST_NOTIFICATION =
      "com.linroid.ketch.app.android.action.REPOST_NOTIFICATION"
  }
}
