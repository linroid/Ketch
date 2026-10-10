package com.linroid.ketch.app.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Binder
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.ServiceCompat
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.ActivityMonitor
import com.linroid.ketch.app.feedback.ActivityRouting
import com.linroid.ketch.app.feedback.ActivitySource
import com.linroid.ketch.app.feedback.SuccessFeedback
import com.linroid.ketch.app.feedback.AndroidFeedbackPlayer
import com.linroid.ketch.app.feedback.AndroidNotifier
import com.linroid.ketch.app.feedback.MessageNotifications
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.feedback.UnreadableFile
import com.linroid.ketch.app.feedback.pairingNotificationCopy
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.LocalServerHandle
import com.linroid.ketch.app.instance.TrackerListStatus
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.app.state.ForegroundPolicy
import com.linroid.ketch.app.state.ForegroundStatus
import com.linroid.ketch.app.state.KeepAwake
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ObservedPeak
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.pauseActiveTasks
import com.linroid.ketch.app.state.toKetchAccent
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.engine.withNetworkInterfaces
import com.linroid.ketch.dash.DashDownloadSource
import com.linroid.ketch.ftp.FtpDownloadSource
import com.linroid.ketch.hls.HlsDownloadSource
import com.linroid.ketch.server.KetchServer
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentDownloadSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@SuppressLint("InlinedApi")
class KetchService : Service() {
  private val log = KetchLogger("KetchService")

  inner class LocalBinder : Binder() {
    val service: KetchService get() = this@KetchService
  }

  lateinit var instanceManager: InstanceManager
    private set

  /**
   * Speed mode of the embedded device. The service owns it, so speed rules keep applying while
   * the app is closed and the ongoing notification's Slow lane button can switch it.
   */
  lateinit var speedMode: SpeedModeController
    private set

  /** Builds discovery providers from the settings the user saves. */
  val aiProviderFactory: AiDiscoveryProviderFactory =
    EmbeddedAiDiscoveryProviderFactory()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val binder = LocalBinder()
  private val toasts = Channel<ActivityEvent>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
  private val speed = MutableStateFlow(0L)
  private val discovering = MutableStateFlow(0)

  // `[power] keepAwake`, as the app's settings last said, and whether the service runs in the
  // foreground: the wake lock follows both.
  private val keepAwake = MutableStateFlow(true)
  private val foreground = MutableStateFlow(false)
  private lateinit var notifier: AndroidNotifier
  private val successFeedback by lazy { SuccessFeedback(AndroidFeedbackPlayer(this)) }
  private var isForeground by foreground::value
  private var isBound = false
  private var isStarted = false
  private val inFront = MutableStateFlow(false)
  private var heldRecovery: ActivityEvent.Recovered? = null
  private var heldUntil: TimeMark? = null
  private var lastStartId = 0
  private var followed: AppController? = null
  private var latestStatus = ForegroundStatus()

  /**
   * What happens on the embedded device while the app is in front, for
   * `App(activityEvents = …)`, which shows it as toasts. Each event goes to one collector.
   */
  val activityEvents: Flow<ActivityEvent> = toasts.receiveAsFlow()

  override fun onCreate() {
    super.onCreate()
    notifier = AndroidNotifier(
      context = this,
      activity = MainActivity::class.java,
      service = KetchService::class.java,
      smallIcon = R.drawable.ic_stat_ketch,
    )
    // Call startForeground() before onCreate returns, to avoid an ANR from the
    // startForegroundService() timeout, so the channels and the first notification, whose text
    // is read from the string resources, are made here. The monitor updates it later.
    val first = runBlocking {
      notifier.setUpChannels()
      notifier.ongoingNotification(emptyList(), serverPort = null, slowLane = false)
    }
    enterForeground(first)
    // No app controller runs yet, so the messages a stopped one notified about are gone.
    notifier.withdrawMessages()

    val app = application as KetchApplication
    val configStore = app.configStore
    val config = configStore.load()
    notifier.accent = config.appearance.accent.toKetchAccent()
    keepAwake.value = config.power.keepAwake
    val driverFactory = DriverFactory(this) { unreadable ->
      app.unreadableFiles.report(UnreadableFile(UnreadableFile.Kind.Downloads, unreadable.movedTo))
    }
    val taskStore = createSqliteTaskStore(driverFactory)
    val instanceName = config.name ?: deviceName()
    val torrentSource = TorrentDownloadSource(
      TorrentConfig(
        stateDirectory = filesDir.resolve("torrent-state").absolutePath,
        additionalTrackers = config.torrent.trackers,
        trackerListUrls = config.torrent.subscribedTrackerLists,
      ),
    )
    instanceManager = InstanceManager(
      factory = InstanceFactory(
        deviceName = instanceName,
        embeddedFactory = {
          val httpEngine = KtorHttpEngine.withNetworkInterfaces(
            getSystemService(ConnectivityManager::class.java)
          )
          Ketch(
            httpEngine = httpEngine,
            taskStore = taskStore,
            config = config.download,
            name = instanceName,
            logger = Logger.combine(
              Logger.console(LogLevel.DEBUG),
              app.fileLogger
            ),
            additionalSources = listOf(
              FtpDownloadSource(), torrentSource,
              HlsDownloadSource(httpEngine), DashDownloadSource(httpEngine)
            ),
          )
        },
        localServerFactory = { ketchApi, pairingRequests ->
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
            allowedDirectories = serverConfig.allowedDirectories,
            mdnsEnabled = serverConfig.mdnsEnabled,
            mdnsRegistrar = AndroidMdnsRegistrar(),
            pairingApprover = { request, address ->
              pairingRequests.ask(request.name, request.code, request.os, address)
            },
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
        applyTorrentSettings = {
          torrentSource.setAdditionalTrackers(it.trackers)
          torrentSource.setTrackerLists(it.subscribedTrackerLists)
        },
        trackerList = torrentSource.trackerLists.map { lists ->
          lists.map {
            TrackerListStatus(it.url.orEmpty(), it.trackers, it.updatedAt, it.failed, it.updating)
          }
        },
        refreshTrackerList = torrentSource::refreshTrackerLists,
      ),
      initialRemotes = config.remotes,
      configStore = configStore,
    )
    val embedded = checkNotNull(instanceManager.embedded) { "No embedded instance" }
    speedMode = SpeedModeController(
      config = { embedded.status().config },
      apply = { embedded.updateConfig(it) },
      scope = scope,
      settings = config.speed,
      observedPeak = ObservedPeak(config.ui.observedPeak, config.ui.observedPeakAt),
      speed = speed,
    )

    saveSpeedMode()
    startForegroundMonitor(embedded)
    startActivityMonitor(embedded)
    startPairingNotices()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isStarted = true
    lastStartId = startId
    when (intent?.action) {
      AndroidNotifier.ACTION_REPOST_NOTIFICATION -> scope.launch {
        if (isForeground && latestStatus.isRequired) enterForeground(ongoing(latestStatus))
      }
      AndroidNotifier.ACTION_PAUSE_ALL -> pauseAll()
      AndroidNotifier.ACTION_SLOW_LANE -> toggleSlowLane()
      AndroidNotifier.ACTION_PAIRING_ALLOW, AndroidNotifier.ACTION_PAIRING_DENY -> {
        val allow = intent.action == AndroidNotifier.ACTION_PAIRING_ALLOW
        val id = AndroidNotifier.pairingIdOf(intent)
        if (id != null) instanceManager.pairingRequests.answer(id, allow)
      }
    }
    return START_STICKY
  }

  /**
   * Tells the service whether the app is in front, as MainActivity is while it is resumed. Events
   * then go to [activityEvents] instead of becoming notifications, and the remote devices not
   * shown stay connected only while it is.
   */
  fun setInFront(inFront: Boolean) {
    this.inFront.value = inFront
    instanceManager.setInForeground(inFront)
    if (!inFront) return
    val held = heldRecovery ?: return
    heldRecovery = null
    // Hours later, such as after Android restarted the service in the background, the downloads
    // it resumed are old news.
    if (heldUntil?.hasNotPassedNow() == true) toasts.trySend(held)
  }

  /**
   * Follows [controller] until it closes. It notifies about the messages the controller posts
   * with `notify`, such as Discover waiting for the user's OK to open a website, while the app is
   * not in front; each notification goes once its message leaves the screen, and tapping it
   * opens [MainActivity], which runs the message's first action. It gives success feedback when a
   * search finds downloads, keeps the service in the foreground while Discover searches, so
   * the search keeps going, and can ask, once the user leaves the app, and follows the keep awake
   * setting.
   */
  fun follow(controller: AppController) {
    followed = controller
    controller.scope.launch {
      MessageNotifications.follow(
        messages = controller.messages,
        notifier = notifier,
        settings = { controller.appSettings.config.notifications },
        inFront = inFront,
      )
    }
    controller.scope.launch {
      successFeedback.follow(controller.state.aiDiscover) {
        controller.appSettings.config.notifications
      }
    }
    controller.scope.launch {
      // The setting keeps its last value once the app closes, as the service keeps running.
      snapshotFlow { controller.appSettings.config.power.keepAwake }
        .collect { if (followed === controller) keepAwake.value = it }
    }
    controller.scope.launch {
      // A controller that closes after the next one started leaves the count to that one.
      try {
        snapshotFlow { controller.state.aiDiscover.sessions.count { it.running } }
          .collect { if (followed === controller) discovering.value = it }
      } finally {
        if (followed === controller) discovering.value = 0
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder {
    isBound = true
    return binder
  }

  override fun onRebind(intent: Intent?) {
    isBound = true
  }

  override fun onUnbind(intent: Intent?): Boolean {
    isBound = false
    if (!isForeground) stop()
    // Asks for onRebind() when a client binds again, such as the activity after a rotation.
    return true
  }

  /** Android 15+ limits data sync foreground services to 6 hours a day. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    log.w { "Reached the time limit for data sync in the foreground, stopping" }
    leaveForeground()
    isStarted = false
    stopSelf()
  }

  override fun onDestroy() {
    super.onDestroy()
    instanceManager.close()
    scope.cancel()
  }

  /**
   * Keeps the service in the foreground while the embedded device downloads or shares itself,
   * whichever device the app shows, or while Discover searches, and refreshes the ongoing
   * notification once a second while it downloads. While it downloads in the foreground, a wake
   * lock keeps the CPU running with the screen off, as the keep awake setting allows.
   */
  private fun startForegroundMonitor(embedded: KetchApi) {
    val statuses = ForegroundPolicy
      .observe(embedded.tasks, instanceManager.serverState, discovering)
      .flowOn(Dispatchers.Default)
      .shareIn(scope, SharingStarted.Eagerly, replay = 1)
    scope.launch {
      KeepAwake.follow(
        inhibitor = WakeLockInhibitor(this@KetchService),
        // Android vitals count a wake lock held without a foreground service as excessive.
        statuses = combine(statuses, foreground) { status, inForeground ->
          if (inForeground) status else ForegroundStatus()
        },
        enabled = keepAwake,
      )
    }
    scope.launch {
      // A new speed mode only relabels the Slow lane button.
      combine(statuses, speedMode.mode) { status, _ -> status }.collectLatest { status ->
        latestStatus = status
        if (!status.isRequired) {
          if (isForeground) {
            log.i { "Leaving the foreground" }
            leaveForeground()
            if (!isBound) stop()
          }
          return@collectLatest
        }
        if (isForeground) {
          notifier.refreshOngoing(ongoing(status))
        } else {
          log.i { "Entering the foreground: $status" }
          enterForeground(ongoing(status))
        }
        keepStarted()
        while (status.downloading > 0) {
          delay(ForegroundPolicy.samplePeriod)
          // The service can leave the foreground meanwhile, such as at its time limit; posting
          // then would leave a notification behind that nothing removes.
          if (!isForeground) break
          speed.value = totalSpeed(embedded.tasks.value)
          notifier.refreshOngoing(ongoing(status))
        }
      }
    }
  }

  /**
   * Reports what happens on the embedded device as the notification settings allow: to the app
   * while it is in front, as notifications otherwise.
   */
  private fun startActivityMonitor(embedded: KetchApi) {
    val source = ActivitySource(LOCAL_DEVICE_ID, embedded.tasks)
    val monitor = ActivityMonitor(flowOf(listOf(source)), scope)
    scope.launch {
      monitor.events.collect { event ->
        val copy = route(event) ?: return@collect
        // Open and Share read the file's details.
        withContext(Dispatchers.IO) { notifier.notify(event, copy) }
      }
    }
  }

  /**
   * Asks about each pairing request that arrives while the app is not in front, where it asks
   * itself, from the notification shade, and withdraws the question once it is answered.
   */
  private fun startPairingNotices() {
    scope.launch {
      instanceManager.pairingRequests.watch(
        onArrived = { ask ->
          if (!inFront.value) notifier.showPairing(ask, pairingNotificationCopy(ask))
        },
        onLeft = notifier::cancelPairing,
      )
    }
  }

  /**
   * Sends [event] to the app if it shows as a toast, gives success feedback for it as the settings allow, and
   * returns its notification, if any.
   */
  private suspend fun route(event: ActivityEvent): NotificationCopy? {
    val config = loadConfig()
    notifier.accent = config.appearance.accent.toKetchAccent()
    val settings = config.notifications
    val delivery = ActivityRouting.deliveryOf(event, settings, inFront.value)
    // A finished download notified in the background alerts as its channel does.
    successFeedback.reported(event, delivery, settings, notificationsAlert = true)
    if (delivery.toast) {
      toasts.trySend(event)
    } else if (event is ActivityEvent.Recovered &&
      ActivityRouting.deliveryOf(event, settings, inFront = true).toast
    ) {
      // The service usually starts a moment before the app comes to the front.
      heldRecovery = event
      heldUntil = TimeSource.Monotonic.markNow() + RECOVERY_HOLD
    }
    return if (delivery.notify) ActivityRouting.copyOf(event) else null
  }

  /** Pauses every queued task, then every downloading one, so the queue cannot start one. */
  private fun pauseAll() {
    val embedded = instanceManager.embedded ?: return
    scope.launch {
      log.i { "Pausing all downloads from the notification" }
      pauseActiveTasks({ embedded.tasks.value }).failures.forEach { (task, e) ->
        log.w { "Couldn't pause taskId=${task.taskId}: ${e.describeCauses()}" }
      }
    }
  }

  private fun toggleSlowLane() {
    scope.launch {
      try {
        val mode = speedMode.toggleSlowLane()
        log.i { "Switched to $mode from the notification" }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // The controller already logged why; the button keeps its label.
        log.d { "Couldn't switch the slow lane: ${e.describeCauses()}" }
      }
    }
  }

  /** Saves the speed mode and the observed peak to `config.toml` as they change. */
  @OptIn(FlowPreview::class)
  private fun saveSpeedMode() {
    scope.launch {
      speedMode.settings.drop(1).collect { speed -> saveConfig { it.copy(speed = speed) } }
    }
    scope.launch {
      // The peak climbs in steps while a download speeds up.
      speedMode.observedPeak.drop(1).debounce(PEAK_SAVE_DELAY).collect { peak ->
        saveConfig {
          it.copy(
            ui = it.ui.copy(
              observedPeak = peak.bytesPerSecond,
              observedPeakAt = peak.atEpochMillis,
            ),
          )
        }
      }
    }
  }

  // Reads and writes run on the main thread, like the app's settings, so they cannot interleave.
  private fun loadConfig(): KetchConfig = try {
    instanceManager.configStore?.load() ?: KetchConfig()
  } catch (e: Exception) {
    log.w { "Couldn't read config.toml: ${e.describeCauses()}" }
    KetchConfig()
  }

  private fun saveConfig(transform: (KetchConfig) -> KetchConfig) {
    val store = instanceManager.configStore ?: return
    try {
      store.save(transform(store.load()))
    } catch (e: Exception) {
      log.w { "Couldn't save config.toml: ${e.describeCauses()}" }
    }
  }

  private suspend fun ongoing(status: ForegroundStatus): Notification =
    notifier.ongoingNotification(
      tasks = instanceManager.embedded?.tasks?.value.orEmpty(),
      serverPort = status.serverPort,
      slowLane = speedMode.mode.value.isSlowLane,
      discovering = status.discovering > 0,
    )

  private fun totalSpeed(tasks: List<DownloadTask>): Long =
    tasks.sumOf { (it.state.value as? DownloadState.Downloading)?.progress?.bytesPerSecond ?: 0L }

  private fun enterForeground(notification: Notification) {
    try {
      ServiceCompat.startForeground(
        this,
        AndroidNotifier.ONGOING_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
      isForeground = true
    } catch (e: IllegalStateException) {
      // Android 12+ refuses to start a foreground service while the app is in the background,
      // e.g. when a scheduled download starts after the service left the foreground.
      log.w { "Could not enter the foreground: ${e.describeCauses()}" }
    }
  }

  private fun leaveForeground() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    isForeground = false
  }

  /**
   * Starts the service while it runs downloads in the foreground. A service that is only bound,
   * e.g. one the activity created after the previous service stopped, is destroyed when the
   * activity unbinds, foreground or not.
   */
  private fun keepStarted() {
    if (isStarted || !isForeground) return
    try {
      startService(Intent(this, KetchService::class.java))
      isStarted = true
    } catch (e: IllegalStateException) {
      log.w { "Could not start the service: ${e.describeCauses()}" }
    }
  }

  /** Stops the service unless a newer start request is on its way. */
  private fun stop() {
    if (stopSelfResult(lastStartId)) isStarted = false
  }

  private companion object {
    const val EVENT_BUFFER = 64
    val PEAK_SAVE_DELAY = 10.seconds
    val RECOVERY_HOLD = 30.seconds
  }
}
