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
import com.linroid.ketch.app.platform.FileActionException
import com.linroid.ketch.app.platform.openFileIntent
import com.linroid.ketch.app.platform.shareFileIntent
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.theme.lightKetchColors

/**
 * Posts Ketch's notifications on Android, in three channels:
 *
 * - [CHANNEL_ACTIVE]: the download service's [ongoing] notification, with the total progress,
 *   the files in flight and Pause all and Slow lane buttons. On Android 16 a single download
 *   shows its connections as progress segments and asks to be promoted to a status bar chip.
 * - [CHANNEL_DONE]: finished downloads with Open and Share, grouped once more than
 *   [GROUP_AFTER] show.
 * - [CHANNEL_FAILED]: failed downloads with Retry.
 *
 * Tapping a notification opens [activity] with a [NotificationLink], which [linkOf] reads back.
 * The ongoing notification's buttons start [service] with [ACTION_PAUSE_ALL] and
 * [ACTION_SLOW_LANE], and dismissing it starts it with [ACTION_REPOST_NOTIFICATION].
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
) : SystemNotifier {
  private val log = KetchLogger("AndroidNotifier")
  private val manager = NotificationManagerCompat.from(context)

  /** Accent the notifications are tinted with, as chosen under Appearance. */
  @Volatile
  var accent: KetchAccent = KetchAccent.Signal

  /** Creates the notification channels and removes the one older versions used. */
  fun createChannels() {
    val system = context.getSystemService(NotificationManager::class.java)
    system.createNotificationChannels(
      listOf(
        channel(CHANNEL_ACTIVE, "Active downloads", NotificationManager.IMPORTANCE_LOW),
        channel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
        channel(CHANNEL_FAILED, "Failed downloads", NotificationManager.IMPORTANCE_HIGH)
      )
    )
    system.deleteNotificationChannel(LEGACY_CHANNEL)
  }

  /**
   * The download service's ongoing notification about [tasks], the embedded device's tasks.
   * Tapping it opens the Downloading tab, or the Waiting tab while every task waits.
   *
   * @param serverPort port of the local server, or `null` when it is not running.
   * @param slowLane whether the slow lane is on, which turns its button into Full speed.
   */
  fun ongoing(tasks: List<DownloadTask>, serverPort: Int?, slowLane: Boolean): Notification {
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
    if (slowLane) builder.setSubText("Slow lane")
    when {
      downloads != null -> describe(builder, downloads)
      serverPort != null -> builder
        .setContentTitle("Sharing this device")
        .setContentText("Server on port $serverPort")
      else -> builder.setContentTitle("Ketch")
    }
    if (downloads != null) {
      builder.addAction(0, "Pause all", serviceIntent(ACTION_PAUSE_ALL))
      val speedLabel = if (slowLane) "Full speed" else "Slow lane"
      builder.addAction(0, speedLabel, serviceIntent(ACTION_SLOW_LANE))
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
          builder.addAction(0, "Retry", showIntent(tag, task = event.taskKey, retry = true))
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

  private fun describe(builder: NotificationCompat.Builder, downloads: OngoingDownloads) {
    builder.setContentTitle(downloads.title).setContentText(downloads.text)
    val lanes = downloads.lanes
    if (lanes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      builder.setStyle(laneStyle(lanes, downloads)).setRequestPromotedOngoing(true)
      downloads.percent?.let { builder.setShortCriticalText("$it%") }
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

  private fun inboxStyle(downloads: OngoingDownloads): NotificationCompat.InboxStyle {
    val style = NotificationCompat.InboxStyle()
    downloads.lines.forEach(style::addLine)
    if (downloads.more > 0) style.setSummaryText("+${downloads.more} more")
    return style
  }

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
    val (label, intent) = try {
      when (action) {
        NotificationAction.Open -> "Open" to openFileIntent(context, path)
        NotificationAction.Share -> "Share" to shareFileIntent(context, path)
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
      .setContentTitle("Finished downloads")
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

  companion object {
    /** Channel of the download service's ongoing notification. */
    const val CHANNEL_ACTIVE: String = "downloads_active"

    /** Channel of finished downloads. */
    const val CHANNEL_DONE: String = "downloads_done"

    /** Channel of failed downloads. */
    const val CHANNEL_FAILED: String = "downloads_failed"

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

    private const val ACTION_SHOW = "com.linroid.ketch.app.action.SHOW"
    private const val ACTION_RETRY = "com.linroid.ketch.app.action.RETRY"
    private const val EXTRA_TASK_KEY = "taskKey"
    private const val EXTRA_STATUS_FILTER = "statusFilter"
    private const val LEGACY_CHANNEL = "ketch_service"
    private const val GROUP_DONE = "downloads_done"
    private const val ONGOING_TAG = "ongoing"
    private const val GROUP_TAG = "done-group"
    private const val ID_TASK = 2
    private const val ID_SUMMARY = 3
    private const val ID_GROUP = 4
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

    /** Withdraws the notification about the task [key]. */
    fun cancel(context: Context, key: TaskKey) {
      NotificationManagerCompat.from(context).cancel(tagOf(key), ID_TASK)
    }

    // A task's newer notification replaces its older one.
    private fun tagOf(key: TaskKey): String = "task:${key.encode()}"

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
