package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ActivityRoutingTest {

  private val request = DownloadRequest("https://example.com/files/ubuntu.iso")
  private val key = TaskKey(LOCAL_DEVICE_ID, "t1")
  private val completed = ActivityEvent.Completed(
    taskKey = key,
    request = request,
    state = DownloadState.Completed(
      outputPath = "/downloads/ubuntu.iso",
      totalBytes = 5_700L * 1024 * 1024 * 1024 / 1000,
      downloadTime = 192.seconds,
    ),
  )
  private val failed = ActivityEvent.Failed(
    taskKey = TaskKey(LOCAL_DEVICE_ID, "t2"),
    request = DownloadRequest("https://example.com/q3-report.pdf"),
    state = DownloadState.Failed(KetchError.Http(403)),
  )

  @Test
  fun deliveryOf_notifyInBackground_notifiesWithoutToast() {
    val delivery = ActivityRouting.deliveryOf(completed, NotificationSettings(), inFront = false)

    assertEquals(ActivityRouting.Delivery(toast = false, notify = true), delivery)
  }

  @Test
  fun deliveryOf_notifyInFront_onlyToasts() {
    val delivery = ActivityRouting.deliveryOf(completed, NotificationSettings(), inFront = true)

    assertEquals(ActivityRouting.Delivery(toast = true, notify = false), delivery)
  }

  @Test
  fun deliveryOf_onlyInBackgroundOff_notifiesInFrontToo() {
    val settings = NotificationSettings(onlyInBackground = false)

    val delivery = ActivityRouting.deliveryOf(failed, settings, inFront = true)

    assertEquals(ActivityRouting.Delivery(toast = true, notify = true), delivery)
  }

  @Test
  fun deliveryOf_inAppInBackground_reportsNothing() {
    val settings = NotificationSettings(finished = NotificationMode.InApp)

    val delivery = ActivityRouting.deliveryOf(completed, settings, inFront = false)

    assertEquals(ActivityRouting.Delivery(toast = false, notify = false), delivery)
  }

  @Test
  fun deliveryOf_failedOff_reportsNothing() {
    val settings = NotificationSettings(failed = NotificationMode.Off)

    val delivery = ActivityRouting.deliveryOf(failed, settings, inFront = true)

    assertEquals(ActivityRouting.Delivery(toast = false, notify = false), delivery)
  }

  @Test
  fun deliveryOf_mutedDevice_reportsNothing() {
    val event = completed.copy(taskKey = TaskKey("nas:8642", "t1"))
    val settings = NotificationSettings(mutedDevices = listOf("nas:8642"))

    val delivery = ActivityRouting.deliveryOf(event, settings, inFront = true)

    assertEquals(ActivityRouting.Delivery(toast = false, notify = false), delivery)
  }

  @Test
  fun deliveryOf_queueDrainedOff_reportsNothing() {
    val event = ActivityEvent.QueueDrained(LOCAL_DEVICE_ID, files = 6, bytes = 1024)
    val settings = NotificationSettings(queueDrained = false)

    val delivery = ActivityRouting.deliveryOf(event, settings, inFront = false)

    assertEquals(ActivityRouting.Delivery(toast = false, notify = false), delivery)
  }

  @Test
  fun deliveryOf_queueDrainedOn_followsFinished() {
    val event = ActivityEvent.QueueDrained(LOCAL_DEVICE_ID, files = 6, bytes = 1024)
    val settings = NotificationSettings(finished = NotificationMode.InApp)

    val delivery = ActivityRouting.deliveryOf(event, settings, inFront = true)

    assertEquals(ActivityRouting.Delivery(toast = true, notify = false), delivery)
  }

  @Test
  fun deliveryOf_deviceOfflineInBackground_neverNotifies() {
    val event = ActivityEvent.DeviceOffline("nas:8642")

    val delivery = ActivityRouting.deliveryOf(event, NotificationSettings(), inFront = false)

    assertEquals(ActivityRouting.Delivery(toast = false, notify = false), delivery)
  }

  @Test
  fun deliveryOf_deviceOfflineSettingOff_skipsToast() {
    val event = ActivityEvent.DeviceOffline("nas:8642")
    val settings = NotificationSettings(deviceOffline = false)

    val delivery = ActivityRouting.deliveryOf(event, settings, inFront = true)

    assertEquals(ActivityRouting.Delivery(toast = false, notify = false), delivery)
  }

  @Test
  fun deliveryOf_addedWithNotifyEverywhere_onlyToasts() {
    val event = ActivityEvent.Added(key, request)
    val settings = NotificationSettings(onlyInBackground = false)

    val delivery = ActivityRouting.deliveryOf(event, settings, inFront = true)

    assertEquals(ActivityRouting.Delivery(toast = true, notify = false), delivery)
  }

  @Test
  fun copyOf_completed_namesFileAndTransferSummary() {
    val copy = ActivityRouting.copyOf(completed)

    assertEquals(
      NotificationCopy(
        title = "Download complete",
        body = "ubuntu.iso · 5.70 GB · took 3m 12s · avg 30.4 MB/s",
        actions = listOf(
          NotificationAction.Open,
          NotificationAction.Reveal,
          NotificationAction.Share,
        ),
      ),
      copy,
    )
  }

  @Test
  fun copyOf_completedWithoutSize_saysHowLongItTook() {
    val event = completed.copy(state = DownloadState.Completed("/downloads/a.bin", null, 5.seconds))

    val copy = ActivityRouting.copyOf(event)

    assertEquals("a.bin · took 5s", copy?.body)
  }

  @Test
  fun copyOf_failed_usesErrorCatalogTitle() {
    val copy = ActivityRouting.copyOf(failed)

    assertEquals(
      NotificationCopy(
        title = "Download failed",
        body = "q3-report.pdf: Access denied (403)",
        actions = listOf(NotificationAction.Retry),
      ),
      copy,
    )
  }

  @Test
  fun copyOf_batch_countsFilesAndBytes() {
    val event = ActivityEvent.CompletedBatch(List(4) { completed })

    val copy = ActivityRouting.copyOf(event)

    assertEquals("4 downloads finished", copy?.title)
    assertEquals("22.80 GB", copy?.body)
  }

  @Test
  fun copyOf_queueDrained_countsFilesAndBytes() {
    val event = ActivityEvent.QueueDrained(LOCAL_DEVICE_ID, files = 6, bytes = 3L * 1024 * 1024)

    val copy = ActivityRouting.copyOf(event)

    assertEquals("All downloads finished", copy?.title)
    assertEquals("6 files · 3.0 MB", copy?.body)
  }

  @Test
  fun copyOf_otherDevice_prefixesTitle() {
    val copy = ActivityRouting.copyOf(completed, deviceName = "NAS-Basement")

    assertEquals("On NAS-Basement: Download complete", copy?.title)
  }

  @Test
  fun copyOf_inAppOnlyEvents_returnsNull() {
    assertNull(ActivityRouting.copyOf(ActivityEvent.Added(key, request)))
    assertNull(ActivityRouting.copyOf(ActivityEvent.Recovered(LOCAL_DEVICE_ID, 3)))
    assertNull(ActivityRouting.copyOf(ActivityEvent.DeviceOffline("nas:8642")))
  }

  @Test
  fun devices_inactiveRemote_watchesOnlyEmbedded() = runTest {
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { FakeKetchApi() }),
    )
    manager.addRemote("nas.local")

    val devices = ActivityRouting.devices(manager).first()

    assertEquals(listOf(LOCAL_DEVICE_ID), devices.map { it.deviceId })
    manager.close()
  }

  @Test
  fun deviceNameOf_eventOnActiveDevice_returnsNull() {
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { FakeKetchApi() }),
    )
    val remote = completed.copy(taskKey = TaskKey("nas.local:8642", "t1"))
    manager.addRemote("nas.local")

    assertNull(ActivityRouting.deviceNameOf(completed, manager))
    assertEquals("nas.local:8642", ActivityRouting.deviceNameOf(remote, manager))
    manager.close()
  }

  @Test
  fun failures_failedAndCanceledTasks_countsOnlyFailed() = runTest {
    val running = ListTestTask("c", ListFixtures.downloading(10))
    val tasks = MutableStateFlow<List<DownloadTask>>(
      listOf(
        ListTestTask("a", DownloadState.Failed(KetchError.Network())),
        ListTestTask("b", DownloadState.Canceled),
        running,
      ),
    )
    val failures = ActivityRouting.failures(
      flowOf(listOf(ActivitySource(LOCAL_DEVICE_ID, tasks), ActivitySource("nas", tasks))),
    )

    assertEquals(2, failures.first())
    running.state.value = DownloadState.Failed(KetchError.Network())
    assertEquals(4, failures.first())
  }
}
