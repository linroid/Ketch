package com.linroid.ketch.app.feedback

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.PairingAsk
import com.linroid.ketch.app.platform.FileActionException
import com.linroid.ketch.app.platform.openFileIntent
import com.linroid.ketch.app.platform.shareFileIntent
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.theme.lightKetchColors
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_open
import ketch.app.shared.generated.resources.action_retry
import ketch.app.shared.generated.resources.notify_action_full_speed
import ketch.app.shared.generated.resources.notify_action_pause_all
import ketch.app.shared.generated.resources.notify_action_share
import ketch.app.shared.generated.resources.notify_action_slow_lane
import ketch.app.shared.generated.resources.notify_channel_active
import ketch.app.shared.generated.resources.notify_channel_done
import ketch.app.shared.generated.resources.notify_channel_failed
import ketch.app.shared.generated.resources.notify_channel_pairing
import ketch.app.shared.generated.resources.notify_channel_requests
import ketch.app.shared.generated.resources.notify_discovering
import ketch.app.shared.generated.resources.notify_finished_group
import ketch.app.shared.generated.resources.notify_more_files
import ketch.app.shared.generated.resources.notify_server_on_port
import ketch.app.shared.generated.resources.notify_sharing_device
import ketch.app.shared.generated.resources.notify_slow_lane_on
import ketch.app.shared.generated.resources.pairing_request_allow
import ketch.app.shared.generated.resources.pairing_request_deny
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant

/**
 * Posts Ketch's notifications on Android, in four channels:
 *
 * - [CHANNEL_ACTIVE]: the download service's [ongoing] notification, with the total progress,
 *   the files in flight and Pause all and Slow lane buttons. On Android 16 a single download
 *   shows its connections as progress segments and asks to be promoted to a status bar chip.
 * - [CHANNEL_DONE]: finished downloads with Open and Share, grouped once more than
 *   [GROUP_AFTER] show.
 * - [CHANNEL_FAILED]: failed downloads with Retry.
 * - [CHANNEL_PAIRING]: devices that want this one's access code, with Allow and Don't allow,
 *   which start [service] with [ACTION_PAIRING_ALLOW] and [ACTION_PAIRING_DENY].
 * - [CHANNEL_REQUESTS]: the app's messages that ask for a notification, such as Discover waiting
 *   for the user's OK to open a website, as long as the message shows.
 *
 * Tapping a notification opens [activity] with a [NotificationLink], which [linkOf] reads back,
 * or, for a message, with the [MessageTap] that [messageTapOf] reads back.
 * The ongoing notification's buttons start [service] with [ACTION_PAUSE_ALL] and
 * [ACTION_SLOW_LANE], and dismissing it starts it with [ACTION_REPOST_NOTIFICATION].
 *
 * Its text is the app's string resources, read in the language of the app's windows: call
 * [setUpChannels] and [ongoingNotification] from a coroutine.
 *
 * @param context context to post from.
 * @param activity the app's activity.
 * @param service the download service.
 * @param smallIcon status bar icon.
 */
class AndroidNotifier(
  private val context: Context,
  private val activity: Class<out Activity>,
  private val service: Class<out Service>,
  @DrawableRes private val smallIcon: Int,
) : SystemNotifier, MessageNotifier {
  private val log = KetchLogger("AndroidNotifier")
  private val manager = NotificationManagerCompat.from(context)

  /** Accent the notifications are tinted with, as chosen under Appearance. */
  @Volatile
  var accent: KetchAccent = KetchAccent.Signal

  // The buttons of the notifications [notify] posts, read once by [setUpChannels] or on first use.
  @Volatile
  private var labels: Labels? = null

  /**
   * Creates the notification channels, named in the app's language, and removes the one older
   * versions used. Calling it again renames them, such as after the language changed.
   */
  suspend fun setUpChannels() {
    val system = context.getSystemService(NotificationManager::class.java)
    val active = Res.string.notify_channel_active.text().load()
    val done = Res.string.notify_channel_done.text().load()
    val failed = Res.string.notify_channel_failed.text().load()
    val pairing = Res.string.notify_channel_pairing.text().load()
    val requests = Res.string.notify_channel_requests.text().load()
    system.createNotificationChannels(
      listOf(
        channel(CHANNEL_ACTIVE, active, NotificationManager.IMPORTANCE_LOW),
        channel(CHANNEL_DONE, done, NotificationManager.IMPORTANCE_DEFAULT),
        channel(CHANNEL_FAILED, failed, NotificationManager.IMPORTANCE_HIGH),
        channel(CHANNEL_PAIRING, pairing, NotificationManager.IMPORTANCE_HIGH),
        // A request holds up what asked for it until the user answers.
        channel(CHANNEL_REQUESTS, requests, NotificationManager.IMPORTANCE_HIGH)
      )
    )
    system.deleteNotificationChannel(LEGACY_CHANNEL)
    labels = Labels.load()
  }

  /**
   * The download service's ongoing notification about [tasks], the embedded device's tasks.
   * Tapping it opens the Downloading tab, or the Waiting tab while every task waits.
   *
   * @param serverPort port of the local server, or `null` when it is not running.
   * @param slowLane whether the slow lane is on, which turns its button into Full speed.
   * @param discovering whether Discover searches, said when nothing else keeps the service.
   */
  suspend fun ongoingNotification(
    tasks: List<DownloadTask>,
    serverPort: Int?,
    slowLane: Boolean,
    discovering: Boolean = false,
  ): Notification {
    val downloads = OngoingDownloads.of(tasks)
    val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVE)
      .setSmallIcon(smallIcon)
      .setColor(accentColor())
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setShowWhen(false)
      .setContentIntent(showIntent(ONGOING_TAG, filter = downloads?.filter))
      .setDeleteIntent(serviceIntent(ACTION_REPOST_NOTIFICATION))
    if (slowLane) builder.setSubText(Res.string.notify_slow_lane_on.text().load())
    when {
      downloads != null -> describe(builder, downloads)
      serverPort != null -> builder
        .setContentTitle(Res.string.notify_sharing_device.text().load())
        .setContentText(Res.string.notify_server_on_port.text(serverPort).load())
      discovering -> builder.setContentTitle(Res.string.notify_discovering.text().load())
      else -> builder.setContentTitle(APP_NAME)
    }
    if (downloads != null) {
      val pauseAll = Res.string.notify_action_pause_all.text().load()
      builder.addAction(0, pauseAll, serviceIntent(ACTION_PAUSE_ALL))
      val speed = if (slowLane) {
        Res.string.notify_action_full_speed
      } else {
        Res.string.notify_action_slow_lane
      }
      builder.addAction(0, speed.text().load(), serviceIntent(ACTION_SLOW_LANE))
    }
    val notification = builder.build()
    notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
    return notification
  }

  /** Replaces the ongoing notification the service shows in the foreground with [notification]. */
  @SuppressLint("MissingPermission")
  fun refreshOngoing(notification: Notification) {
    // Without permission, Android keeps showing the service's notification in the task manager.
    if (manager.areNotificationsEnabled()) manager.notify(ONGOING_ID, notification)
  }

  override fun notify(event: ActivityEvent, copy: NotificationCopy) {
    if (!manager.areNotificationsEnabled()) {
      log.d { "Notifications are off, not posting \"${copy.title}\"" }
      return
    }
    when (event) {
      is ActivityEvent.Completed -> {
        val tag = tagOf(event.taskKey)
        val builder = builder(CHANNEL_DONE, copy)
          .setGroup(GROUP_DONE)
          .setContentIntent(showIntent(tag, task = event.taskKey))
        copy.actions.mapNotNull { fileAction(it, event.state.outputPath, tag) }
          .forEach(builder::addAction)
        post(tag, ID_TASK, builder.build())
        groupDone(tag)
      }
      is ActivityEvent.Failed -> {
        val tag = tagOf(event.taskKey)
        val builder = builder(CHANNEL_FAILED, copy)
          .setCategory(NotificationCompat.CATEGORY_ERROR)
          .setContentIntent(showIntent(tag, task = event.taskKey))
        if (NotificationAction.Retry in copy.actions) {
          val retry = labels().retry
          builder.addAction(0, retry, showIntent(tag, task = event.taskKey, retry = true))
        }
        post(tag, ID_TASK, builder.build())
      }
      is ActivityEvent.CompletedBatch, is ActivityEvent.QueueDrained -> {
        // A device's newer summary replaces its older one.
        val tag = "device:${ActivityRouting.deviceIdOf(event)}"
        val builder = builder(CHANNEL_DONE, copy)
          .setGroup(GROUP_DONE)
          .setContentIntent(showIntent(tag, filter = StatusFilter.Done))
        post(tag, ID_SUMMARY, builder.build())
        groupDone(tag)
      }
      is ActivityEvent.Added,
      is ActivityEvent.Recovered,
      is ActivityEvent.DeviceOffline,
      is ActivityEvent.DeviceOnline -> log.d { "No notification for ${event::class.simpleName}" }
    }
  }

  override fun post(message: AppMessage, copy: NotificationCopy) {
    if (!manager.areNotificationsEnabled()) {
      // A message that notifies holds up what posted it until the user answers.
      log.i { "Notifications are off, not posting message ${message.id}" }
      return
    }
    val tag = messageTag(message.id)
    val builder = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
      .setSmallIcon(smallIcon)
      .setColor(accentColor())
      .setContentTitle(copy.title)
      .setContentIntent(messageIntent(tag, message))
      .setAutoCancel(true)
    if (copy.body.isNotEmpty()) {
      builder.setContentText(copy.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
    }
    post(tag, ID_MESSAGE, builder.build())
  }

  override fun withdraw(id: Long) {
    manager.cancel(messageTag(id), ID_MESSAGE)
  }

  /**
   * Withdraws the notifications of messages left from an earlier run of the app, whose messages,
   * and the buttons they led to, are gone.
   */
  fun withdrawMessages() {
    manager.activeNotifications
      .filter { it.id == ID_MESSAGE && it.tag?.startsWith(MESSAGE_TAG_PREFIX) == true }
      .forEach { manager.cancel(it.tag, it.id) }
  }

  private suspend fun describe(builder: NotificationCompat.Builder, downloads: OngoingDownloads) {
    builder.setContentTitle(downloads.title.load()).setContentText(downloads.text?.load())
    val lanes = downloads.lanes
    if (lanes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      builder.setStyle(laneStyle(lanes, downloads)).setRequestPromotedOngoing(true)
      downloads.percent?.let { builder.setShortCriticalText(percentText(it).load()) }
    } else {
      val permille = downloads.permille
      builder.setProgress(OngoingDownloads.PROGRESS_MAX, permille ?: 0, permille == null)
        .setStyle(inboxStyle(downloads))
    }
  }

  // One segment per connection range, filled with the accent as far as the download got.
  private fun laneStyle(lanes: List<Int>, downloads: OngoingDownloads): NotificationCompat.Style {
    val color = accentColor()
    val segments = lanes.map { NotificationCompat.ProgressStyle.Segment(it).setColor(color) }
    return NotificationCompat.ProgressStyle()
      .setProgressSegments(segments)
      .setProgressIndeterminate(downloads.permille == null)
      .setProgress(downloads.lanePosition)
  }

  private suspend fun inboxStyle(downloads: OngoingDownloads): NotificationCompat.InboxStyle {
    val style = NotificationCompat.InboxStyle()
    downloads.lines.forEach { style.addLine(it.load()) }
    if (downloads.more > 0) {
      style.setSummaryText(Res.plurals.notify_more_files.text(downloads.more).load())
    }
    return style
  }

  /**
   * Asks about [ask], a device that wants this one's access code, with [copy] and Allow and
   * Don't allow buttons; a tap opens the app, which asks too. [cancelPairing] withdraws it.
   */
  suspend fun showPairing(ask: PairingAsk, copy: NotificationCopy) {
    if (!manager.areNotificationsEnabled()) {
      log.d { "Notifications are off, not asking about pairing" }
      return
    }
    val tag = pairingTag(ask.id)
    val allow = Res.string.pairing_request_allow.text().load()
    val deny = Res.string.pairing_request_deny.text().load()
    val notification = builder(CHANNEL_PAIRING, copy)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setTimeoutAfter(PAIRING_TIMEOUT_MS)
      .setContentIntent(showIntent(tag))
      .addAction(0, deny, pairingIntent(ask.id, ACTION_PAIRING_DENY))
      .addAction(0, allow, pairingIntent(ask.id, ACTION_PAIRING_ALLOW))
      .build()
    post(tag, ID_PAIRING, notification)
  }

  /** Withdraws the notification about the pairing request [id], once it is answered or gone. */
  fun cancelPairing(id: Long) {
    manager.cancel(pairingTag(id), ID_PAIRING)
  }

  private fun pairingIntent(id: Long, action: String): PendingIntent = PendingIntent.getService(
    context,
    requestCode(pairingTag(id), action),
    Intent(context, service).setAction(action).putExtra(EXTRA_PAIRING_ID, id),
    FLAGS
  )

  private fun builder(channel: String, copy: NotificationCopy): NotificationCompat.Builder =
    NotificationCompat.Builder(context, channel)
      .setSmallIcon(smallIcon)
      .setColor(accentColor())
      .setContentTitle(copy.title)
      .setContentText(copy.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
      .setAutoCancel(true)

  // Android has no file manager to reveal a download in, so only Open and Share become buttons.
  private fun fileAction(
    action: NotificationAction,
    path: String,
    tag: String,
  ): NotificationCompat.Action? {
    val labels = labels()
    val (label, intent) = try {
      when (action) {
        NotificationAction.Open -> labels.open to openFileIntent(context, path)
        NotificationAction.Share -> labels.share to shareFileIntent(context, path)
        NotificationAction.Reveal, NotificationAction.Retry -> return null
      }
    } catch (e: FileActionException) {
      log.d { "No $action button: ${e.describeCauses()}" }
      return null
    }
    val pending = PendingIntent.getActivity(context, requestCode(tag, action.name), intent, FLAGS)
    return NotificationCompat.Action.Builder(0, label, pending).build()
  }

  // Bundles the finished downloads under a summary once more than GROUP_AFTER of them show.
  private fun groupDone(posted: String) {
    val shown = manager.activeNotifications
      .filter { it.notification.group == GROUP_DONE }
      .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
      .mapNotNullTo(HashSet()) { it.tag } + posted
    if (shown.size <= GROUP_AFTER) return
    val summary = NotificationCompat.Builder(context, CHANNEL_DONE)
      .setSmallIcon(smallIcon)
      .setColor(accentColor())
      .setContentTitle(labels().finished)
      .setGroup(GROUP_DONE)
      .setGroupSummary(true)
      .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      .setContentIntent(showIntent(GROUP_TAG, filter = StatusFilter.Done))
      .setAutoCancel(true)
      .build()
    post(GROUP_TAG, ID_GROUP, summary)
  }

  @SuppressLint("MissingPermission")
  private fun post(tag: String, id: Int, notification: Notification) {
    try {
      manager.notify(tag, id, notification)
    } catch (e: SecurityException) {
      // The permission can be revoked between the check and the post.
      log.d { "Couldn't post a notification: ${e.describeCauses()}" }
    }
  }

  private fun showIntent(
    tag: String,
    task: TaskKey? = null,
    filter: StatusFilter? = null,
    retry: Boolean = false,
  ): PendingIntent {
    val action = if (retry) ACTION_RETRY else ACTION_SHOW
    val intent = Intent(context, activity)
      .setAction(action)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    task?.let { intent.putExtra(EXTRA_TASK_KEY, it.encode()) }
    filter?.let { intent.putExtra(EXTRA_STATUS_FILTER, it.name) }
    return PendingIntent.getActivity(context, requestCode(tag, action), intent, FLAGS)
  }

  private fun messageIntent(tag: String, message: AppMessage): PendingIntent {
    val intent = Intent(context, activity)
      .setAction(ACTION_MESSAGE)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .putExtra(EXTRA_MESSAGE_ID, message.id)
      .putExtra(EXTRA_MESSAGE_AT, message.at.toEpochMilliseconds())
    return PendingIntent.getActivity(context, requestCode(tag, ACTION_MESSAGE), intent, FLAGS)
  }

  private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
    context,
    requestCode(ONGOING_TAG, action),
    Intent(context, service).setAction(action),
    FLAGS
  )

  // The notification shade follows the system's dark mode, not the app's.
  private fun accentColor(): Int {
    val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val colors = if (mode == Configuration.UI_MODE_NIGHT_YES) {
      darkKetchColors(accent)
    } else {
      lightKetchColors(accent)
    }
    return colors.accent.toArgb()
  }

  private fun channel(id: String, name: String, importance: Int): NotificationChannel =
    NotificationChannel(id, name, importance).apply { setShowBadge(id != CHANNEL_ACTIVE) }

  // Read on first use when [setUpChannels] has not run yet; [notify] runs off the main thread.
  private fun labels(): Labels = labels ?: runBlocking { Labels.load() }.also { labels = it }

  /** The buttons and titles of the notifications [notify] posts, in the app's language. */
  private class Labels(
    val open: String,
    val share: String,
    val retry: String,
    val finished: String,
  ) {
    companion object {
      suspend fun load(): Labels = Labels(
        open = Res.string.action_open.text().load(),
        share = Res.string.notify_action_share.text().load(),
        retry = Res.string.action_retry.text().load(),
        finished = Res.string.notify_finished_group.text().load(),
      )
    }
  }

  companion object {
    /** Channel of the download service's ongoing notification. */
    const val CHANNEL_ACTIVE: String = "downloads_active"

    /** Channel of finished downloads. */
    const val CHANNEL_DONE: String = "downloads_done"

    /** Channel of failed downloads. */
    const val CHANNEL_FAILED: String = "downloads_failed"

    /** Channel of devices that want this one's access code. */
    const val CHANNEL_PAIRING: String = "pairing_requests"

    /** Asks the service to let in the device of the pairing request in [pairingIdOf]. */
    const val ACTION_PAIRING_ALLOW: String = "com.linroid.ketch.app.action.PAIRING_ALLOW"

    /** Asks the service to turn away the device of the pairing request in [pairingIdOf]. */
    const val ACTION_PAIRING_DENY: String = "com.linroid.ketch.app.action.PAIRING_DENY"
    /** Channel of the app's messages that wait for the user. */
    const val CHANNEL_REQUESTS: String = "requests"

    /** Id of the ongoing notification, which the service shows in the foreground. */
    const val ONGOING_ID: Int = 1

    /** Asks the service to pause every running and queued download. */
    const val ACTION_PAUSE_ALL: String = "com.linroid.ketch.app.action.PAUSE_ALL"

    /** Asks the service to turn the slow lane on, or off when it is on. */
    const val ACTION_SLOW_LANE: String = "com.linroid.ketch.app.action.SLOW_LANE"

    /** Asks the service to post the ongoing notification again after the user dismissed it. */
    const val ACTION_REPOST_NOTIFICATION: String =
      "com.linroid.ketch.app.action.REPOST_NOTIFICATION"

    /** Bundles finished downloads once more than this many show. */
    const val GROUP_AFTER: Int = 3

    private const val APP_NAME = "Ketch"
    private const val ACTION_SHOW = "com.linroid.ketch.app.action.SHOW"
    private const val ACTION_RETRY = "com.linroid.ketch.app.action.RETRY"
    private const val ACTION_MESSAGE = "com.linroid.ketch.app.action.MESSAGE"
    private const val EXTRA_TASK_KEY = "taskKey"
    private const val EXTRA_STATUS_FILTER = "statusFilter"
    private const val EXTRA_MESSAGE_ID = "messageId"
    private const val EXTRA_MESSAGE_AT = "messageAt"
    private const val MESSAGE_TAG_PREFIX = "message:"
    private const val LEGACY_CHANNEL = "ketch_service"
    private const val GROUP_DONE = "downloads_done"
    private const val ONGOING_TAG = "ongoing"
    private const val GROUP_TAG = "done-group"
    private const val ID_TASK = 2
    private const val ID_SUMMARY = 3
    private const val ID_GROUP = 4
    private const val ID_PAIRING = 5
    private const val ID_MESSAGE = 6
    private const val EXTRA_PAIRING_ID = "pairingId"

    // A request expires on the server after two minutes; its notification goes with it.
    private const val PAIRING_TIMEOUT_MS = 2 * 60 * 1000L
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /**
     * What the notification tap that opened [intent] asks for, or `null` when it is not one or
     * only opens the app, as the ongoing notification does while nothing downloads.
     */
    fun linkOf(intent: Intent?): NotificationLink? {
      val action = intent?.action
      if (action != ACTION_SHOW && action != ACTION_RETRY) return null
      val name = intent.getStringExtra(EXTRA_STATUS_FILTER)
      val taskKey = intent.getStringExtra(EXTRA_TASK_KEY)?.let(TaskKey::decode)
      val filter = StatusFilter.entries.firstOrNull { it.name == name }
      if (taskKey == null && filter == null) return null
      return NotificationLink(taskKey, filter, retry = action == ACTION_RETRY)
    }

    /** The pairing request whose Allow or Don't allow started the service with [intent]. */
    fun pairingIdOf(intent: Intent?): Long? =
      intent?.takeIf { it.hasExtra(EXTRA_PAIRING_ID) }?.getLongExtra(EXTRA_PAIRING_ID, 0L)
    /** The message whose notification was tapped to open [intent], or `null` when it is not one. */
    fun messageTapOf(intent: Intent?): MessageTap? {
      if (intent?.action != ACTION_MESSAGE) return null
      val id = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
      if (id <= 0L || !intent.hasExtra(EXTRA_MESSAGE_AT)) return null
      val at = Instant.fromEpochMilliseconds(intent.getLongExtra(EXTRA_MESSAGE_AT, 0L))
      return MessageTap(id, at)
    }

    /** Withdraws the notification about the task [key]. */
    fun cancel(context: Context, key: TaskKey) {
      NotificationManagerCompat.from(context).cancel(tagOf(key), ID_TASK)
    }

    // A task's newer notification replaces its older one.
    private fun tagOf(key: TaskKey): String = "task:${key.encode()}"

    private fun pairingTag(id: Long): String = "pairing:$id"
    private fun messageTag(id: Long): String = "$MESSAGE_TAG_PREFIX$id"

    // PendingIntents differing only in extras are the same one, so each gets its own code.
    private fun requestCode(tag: String, what: String): Int = "$tag/$what".hashCode()
  }
}

/**
 * What a tap on one of Ketch's notifications asks the app to show.
 *
 * @property taskKey task to show in the inspector; `null` for a status tab.
 * @property filter status tab to show, such as Downloading for the ongoing notification.
 * @property retry whether Retry was tapped, which retries [taskKey].
 */
data class NotificationLink(
  val taskKey: TaskKey?,
  val filter: StatusFilter?,
  val retry: Boolean,
)

/**
 * A tap on the notification of an app message, for [MessageNotifications.open].
 *
 * @property id the message's id.
 * @property postedAt when it was posted, to the millisecond.
 */
data class MessageTap(
  val id: Long,
  val postedAt: Instant,
)
