package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MessageNotificationsTest {

  private val now = Instant.parse("2026-10-03T09:30:00.123456Z")
  private val clock = object : Clock {
    override fun now(): Instant = now
  }

  private fun message(
    notify: Boolean = true,
    deviceId: String? = null,
  ) = AppMessage(
    id = 1,
    level = MessageLevel.Info,
    title = verbatim("Discover needs your OK to open blender.org"),
    deviceId = deviceId,
    at = now,
    toast = ToastMode.Sticky,
    notify = notify,
  )

  /** Records what is posted and withdrawn. */
  private class RecordingNotifier : MessageNotifier {
    val posted = mutableListOf<NotificationCopy>()
    val withdrawn = mutableListOf<Long>()

    override fun post(message: AppMessage, copy: NotificationCopy) {
      posted += copy
    }

    override fun withdraw(id: Long) {
      withdrawn += id
    }
  }

  private fun TestScope.follow(
    center: MessageCenter,
    notifier: MessageNotifier,
    settings: NotificationSettings = NotificationSettings(),
    inFront: Flow<Boolean> = MutableStateFlow(false),
  ) = backgroundScope.launch {
    MessageNotifications.follow(center, notifier, { settings }, inFront)
  }.also { runCurrent() }

  private fun MessageCenter.ask(host: String = "blender.org"): AppMessage = post(
    level = MessageLevel.Info,
    title = verbatim("Discover needs your OK to open $host"),
    toast = ToastMode.Sticky,
    notify = true,
  )

  @Test
  fun notifies_eachCase_followsTheSettings() {
    val inAppOnly = NotificationSettings(
      finished = NotificationMode.InApp,
      failed = NotificationMode.Off,
    )
    val cases = listOf(
      Triple(message(), NotificationSettings(), false) to true,
      // In front the toast shows it, whatever the download reports do.
      Triple(message(), NotificationSettings(), true) to false,
      Triple(message(), NotificationSettings(onlyInBackground = false), true) to false,
      Triple(message(notify = false), NotificationSettings(), false) to false,
      // A request that waits for the user is not a download report.
      Triple(message(), inAppOnly, false) to true,
      Triple(
        message(deviceId = "nas"),
        NotificationSettings(mutedDevices = listOf("nas")),
        false,
      ) to false,
      Triple(message(), NotificationSettings(mutedDevices = listOf("nas")), false) to true,
    )
    for ((input, expected) in cases) {
      val (message, settings, inFront) = input
      assertEquals(
        expected,
        MessageNotifications.notifies(message, settings, inFront),
        "$settings inFront=$inFront notify=${message.notify} device=${message.deviceId}",
      )
    }
  }

  @Test
  fun copyOf_messageWithDetail_putsItInTheBody() = runTest {
    val message = message().copy(detail = verbatim("Blender 4.2 for Apple silicon"))

    assertEquals(
      NotificationCopy(
        title = "Discover needs your OK to open blender.org",
        body = "Blender 4.2 for Apple silicon",
      ),
      MessageNotifications.copyOf(message),
    )
  }

  @Test
  fun copyOf_messageWithoutDetail_leavesTheBodyEmpty() = runTest {
    assertEquals("", MessageNotifications.copyOf(message()).body)
  }

  @Test
  fun follow_postedInBackground_notifiesUntilTheMessageLeaves() = runTest {
    val center = MessageCenter()
    val notifier = RecordingNotifier()
    follow(center, notifier)

    val asked = center.ask()
    runCurrent()
    center.post(MessageLevel.Info, verbatim("Slow lane on"))
    runCurrent()

    assertEquals(
      listOf(NotificationCopy("Discover needs your OK to open blender.org", "")),
      notifier.posted,
    )
    assertEquals(emptyList(), notifier.withdrawn)

    center.withdraw(asked.id)
    runCurrent()

    assertEquals(listOf(asked.id), notifier.withdrawn)
  }

  @Test
  fun follow_postedInFront_notifiesOnceWhenTheAppLeavesWhileItShows() = runTest {
    val center = MessageCenter()
    val notifier = RecordingNotifier()
    val inFront = MutableStateFlow(true)
    follow(center, notifier, inFront = inFront)

    center.ask()
    runCurrent()
    assertEquals(emptyList(), notifier.posted, "In front the toast shows it")
    inFront.value = false
    runCurrent()
    assertEquals(1, notifier.posted.size)

    // Coming back and leaving again never raises it twice.
    inFront.value = true
    runCurrent()
    inFront.value = false
    runCurrent()
    assertEquals(1, notifier.posted.size)
    assertEquals(emptyList(), notifier.withdrawn)
  }

  @Test
  fun follow_answeredInFront_neverNotifies() = runTest {
    val center = MessageCenter()
    val notifier = RecordingNotifier()
    val inFront = MutableStateFlow(true)
    follow(center, notifier, inFront = inFront)
    val asked = center.ask()
    runCurrent()

    center.withdraw(asked.id)
    runCurrent()
    inFront.value = false
    runCurrent()

    assertEquals(emptyList(), notifier.posted)
  }

  @Test
  fun follow_toastDismissed_withdrawsTheNotification() = runTest {
    val center = MessageCenter()
    val notifier = RecordingNotifier()
    follow(center, notifier)
    val first = center.ask("blender.org")
    runCurrent()
    val second = center.ask("github.com")
    runCurrent()

    center.dismiss(first.id)
    runCurrent()

    assertEquals(2, notifier.posted.size)
    assertEquals(listOf(first.id), notifier.withdrawn)
    assertTrue(second.id !in notifier.withdrawn)
  }

  @Test
  fun follow_downloadReportsOff_stillNotifiesRequests() = runTest {
    val center = MessageCenter()
    val notifier = RecordingNotifier()
    val off = NotificationSettings(finished = NotificationMode.Off, failed = NotificationMode.Off)
    follow(center, notifier, settings = off)

    center.ask()
    runCurrent()

    assertEquals(1, notifier.posted.size)
  }

  @Test
  fun follow_mutedDevice_postsNothing() = runTest {
    val center = MessageCenter()
    val notifier = RecordingNotifier()
    follow(center, notifier, settings = NotificationSettings(mutedDevices = listOf("nas")))

    center.post(
      level = MessageLevel.Info,
      title = verbatim("The NAS needs your OK"),
      deviceId = "nas",
      toast = ToastMode.Sticky,
      notify = true,
    )
    runCurrent()

    assertEquals(emptyList(), notifier.posted)
  }

  @Test
  fun follow_canceled_withdrawsWhatIsStillUp() = runTest {
    val center = MessageCenter()
    val notifier = RecordingNotifier()
    val job = follow(center, notifier)
    val asked = center.ask()
    runCurrent()

    job.cancel()
    runCurrent()

    assertEquals(listOf(asked.id), notifier.withdrawn)
  }

  @Test
  fun open_messageOnScreen_runsItsFirstActionAndDismissesIt() {
    val center = MessageCenter(clock)
    val clicked = mutableListOf<String>()
    val message = center.post(
      level = MessageLevel.Info,
      title = verbatim("Discover needs your OK to open blender.org"),
      actions = listOf("Review", "Deny").map { label ->
        MessageAction(verbatim(label)) { clicked += label }
      },
      toast = ToastMode.Sticky,
      notify = true,
    )

    // A notification keeps the time to the millisecond.
    val opened = MessageNotifications.open(
      center,
      message.id,
      Instant.fromEpochMilliseconds(now.toEpochMilliseconds()),
    )

    assertTrue(opened)
    assertEquals(listOf("Review"), clicked)
    assertEquals(emptyList(), center.active.value)
  }

  @Test
  fun open_messageOfAnEarlierRun_isLeftAlone() {
    val center = MessageCenter(clock)
    var clicked = false
    val message = center.post(
      level = MessageLevel.Info,
      title = verbatim("Removed 5 downloads"),
      actions = listOf(MessageAction(verbatim("Undo")) { clicked = true }),
      notify = true,
    )

    val opened = MessageNotifications.open(center, message.id, now - 3.seconds)

    assertFalse(opened)
    assertFalse(clicked)
    assertEquals(listOf(message), center.active.value)
  }
}
