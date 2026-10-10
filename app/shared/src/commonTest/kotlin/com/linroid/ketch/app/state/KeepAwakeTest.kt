package com.linroid.ketch.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAwakeTest {

  @Test
  fun keepsAwake_downloadingOrQueued_isTrue() {
    assertTrue(ForegroundStatus(downloading = 1).keepsAwake)
    assertTrue(ForegroundStatus(queued = 2).keepsAwake)
  }

  @Test
  fun keepsAwake_serverOrDiscoverOnly_isFalse() {
    assertFalse(ForegroundStatus(serverPort = 9000).keepsAwake)
    assertFalse(ForegroundStatus(discovering = 1).keepsAwake)
  }

  @Test
  fun follow_downloadsStartAndEnd_acquiresThenReleases() = runTest {
    val statuses = MutableStateFlow(ForegroundStatus())
    val inhibitor = RecordingInhibitor()
    val job = backgroundScope.launch {
      KeepAwake.follow(inhibitor, statuses, MutableStateFlow(true))
    }
    runCurrent()
    assertFalse(inhibitor.held)

    statuses.value = ForegroundStatus(downloading = 1)
    runCurrent()
    assertTrue(inhibitor.held)

    statuses.value = ForegroundStatus()
    runCurrent()
    assertFalse(inhibitor.held)
    job.cancel()
  }

  @Test
  fun follow_busyStatusChanges_acquiresOnce() = runTest {
    val statuses = MutableStateFlow(ForegroundStatus(downloading = 1))
    val inhibitor = RecordingInhibitor()
    backgroundScope.launch { KeepAwake.follow(inhibitor, statuses, MutableStateFlow(true)) }
    runCurrent()

    statuses.value = ForegroundStatus(downloading = 2, queued = 1)
    runCurrent()
    statuses.value = ForegroundStatus(queued = 1, serverPort = 9000)
    runCurrent()
    assertEquals(listOf("acquire"), inhibitor.calls)
  }

  @Test
  fun follow_settingTurnedOffWhileDownloading_releasesAndHoldsAgainWhenOn() = runTest {
    val enabled = MutableStateFlow(true)
    val inhibitor = RecordingInhibitor()
    backgroundScope.launch {
      KeepAwake.follow(inhibitor, MutableStateFlow(ForegroundStatus(downloading = 1)), enabled)
    }
    runCurrent()
    assertTrue(inhibitor.held)

    enabled.value = false
    runCurrent()
    assertFalse(inhibitor.held)

    enabled.value = true
    runCurrent()
    assertTrue(inhibitor.held)
  }

  @Test
  fun follow_settingOff_neverAcquires() = runTest {
    val inhibitor = RecordingInhibitor()
    backgroundScope.launch {
      KeepAwake.follow(
        inhibitor,
        MutableStateFlow(ForegroundStatus(downloading = 3)),
        MutableStateFlow(false),
      )
    }
    runCurrent()
    assertFalse("acquire" in inhibitor.calls)
  }

  @Test
  fun follow_cancelledWhileHolding_releases() = runTest {
    val inhibitor = RecordingInhibitor()
    val job = backgroundScope.launch {
      KeepAwake.follow(
        inhibitor,
        MutableStateFlow(ForegroundStatus(downloading = 1)),
        MutableStateFlow(true),
      )
    }
    runCurrent()
    assertTrue(inhibitor.held)

    job.cancel()
    runCurrent()
    assertFalse(inhibitor.held)
  }

  private class RecordingInhibitor : SleepInhibitor {
    val calls = mutableListOf<String>()
    var held = false
      private set

    override fun acquire() {
      calls += "acquire"
      held = true
    }

    override fun release() {
      calls += "release"
      held = false
    }
  }
}
