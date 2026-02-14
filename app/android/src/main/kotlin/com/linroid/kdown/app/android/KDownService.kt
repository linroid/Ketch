package com.linroid.kdown.app.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.linroid.kdown.app.instance.InstanceFactory
import com.linroid.kdown.app.instance.InstanceManager
import com.linroid.kdown.app.instance.LocalServerHandle
import com.linroid.kdown.app.instance.ServerState
import com.linroid.kdown.core.log.KDownLogger
import com.linroid.kdown.server.KDownServer
import com.linroid.kdown.server.KDownServerConfig
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.createSqliteTaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KDownService : Service() {

  inner class LocalBinder : Binder() {
    val service: KDownService get() = this@KDownService
  }

  lateinit var instanceManager: InstanceManager
    private set

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val binder = LocalBinder()
  private var isForeground = false
  private var isBound = false
  private var latestActiveCount = 0
  private var latestServerState: ServerState = ServerState.Stopped
  private var shouldStayForeground = false

  override fun onCreate() {
    super.onCreate()
    val taskStore = createSqliteTaskStore(DriverFactory(this))
    val downloadsDir = Environment
      .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      .absolutePath
    instanceManager = InstanceManager(
      InstanceFactory(
        taskStore = taskStore,
        defaultDirectory = downloadsDir,
        deviceName = android.os.Build.MODEL,
        localServerFactory = { port, apiToken, kdownApi ->
          KDownLogger.i(TAG) { "Starting local server on port $port" }
          val server = KDownServer(
            kdownApi,
            KDownServerConfig(
              port = port,
              apiToken = apiToken,
              corsAllowedHosts = listOf("*"),
            ),
          )
          server.start(wait = false)
          KDownLogger.i(TAG) { "Local server started on port $port" }
          object : LocalServerHandle {
            override fun stop() {
              KDownLogger.i(TAG) { "Stopping local server" }
              server.stop()
              KDownLogger.i(TAG) { "Local server stopped" }
            }
          }
        },
      ),
    )
    createNotificationChannel()
    startForegroundMonitor()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_REPOST_NOTIFICATION && isForeground && shouldStayForeground) {
      val notification = buildNotification(latestActiveCount, latestServerState)
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
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
      "KDown Service",
      NotificationManager.IMPORTANCE_LOW,
    )
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun startForegroundMonitor() {
    scope.launch {
      combine(
        instanceManager.activeApi.flatMapLatest { api ->
          api.tasks.map { tasks ->
            tasks.count { it.state.value.isActive }
          }
        },
        instanceManager.serverState,
      ) { activeCount, serverState ->
        Triple(activeCount, serverState, activeCount > 0 || serverState is ServerState.Running)
      }.collect { (activeCount, serverState, shouldBeForeground) ->
        latestActiveCount = activeCount
        latestServerState = serverState
        shouldStayForeground = shouldBeForeground
        if (shouldBeForeground) {
          val notification = buildNotification(activeCount, serverState)
          if (!isForeground) {
            KDownLogger.i(TAG) { "Start notification" }
          }
          ServiceCompat.startForeground(
            this@KDownService,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
          )
          isForeground = true
        } else if (isForeground) {
          ServiceCompat.stopForeground(
            this@KDownService,
            ServiceCompat.STOP_FOREGROUND_REMOVE,
          )
          isForeground = false
          if (!isBound) stopSelf()
        }
      }
    }
  }

  private fun buildNotification(
    activeCount: Int,
    serverState: ServerState,
  ): android.app.Notification {
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )
    val text = buildString {
      if (activeCount > 0) {
        append("Downloading $activeCount file")
        if (activeCount > 1) append("s")
      }
      if (serverState is ServerState.Running) {
        if (activeCount > 0) append(" · ")
        append("Server on :${serverState.port}")
      }
    }
    val deleteIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, KDownService::class.java).setAction(ACTION_REPOST_NOTIFICATION),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("KDown")
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

  companion object {
    private const val TAG = "KDownService"
    private const val CHANNEL_ID = "kdown_service"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_REPOST_NOTIFICATION = "com.linroid.kdown.app.android.action.REPOST_NOTIFICATION"
  }
}
