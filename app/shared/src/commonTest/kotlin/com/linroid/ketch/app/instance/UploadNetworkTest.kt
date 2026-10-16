package com.linroid.ketch.app.instance

import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.TorrentUploadMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class UploadNetworkTest {

  /** What [followUploadNetwork] is fed and what it asks for, run by the test's scheduler. */
  private class Follower(private val scope: TestScope) {
    val settings = MutableStateFlow(TorrentSettings(uploadWifiOnly = true))
    val network = MutableSharedFlow<Boolean>(replay = 1)
    val calls = mutableListOf<Boolean>()
    var failing = false

    init {
      scope.backgroundScope.launch {
        followUploadNetwork(settings, network) { paused ->
          if (failing) throw IllegalStateException("Engine closed")
          calls += paused
        }
      }
      scope.runCurrent()
    }

    fun report(unmetered: Boolean) {
      network.tryEmit(unmetered)
      scope.runCurrent()
    }
  }

  @Test
  fun uploadsPaused_wifiOnlyOnAMeteredNetwork_pausesWhateverTheMode() {
    for (mode in TorrentUploadMode.entries) {
      val settings = TorrentSettings(upload = mode, uploadWifiOnly = true)
      assertTrue(uploadsPaused(settings, unmetered = false), "$mode")
      assertFalse(uploadsPaused(settings, unmetered = true), "$mode")
    }
  }

  @Test
  fun uploadsPaused_wifiOnlyOff_neverPauses() {
    val settings = TorrentSettings(uploadWifiOnly = false)

    assertFalse(uploadsPaused(settings, unmetered = false))
    assertFalse(uploadsPaused(settings, unmetered = true))
  }

  @Test
  fun followUploadNetwork_beforeTheNetworkReports_callsNothing() = runTest {
    val follower = Follower(this)

    advanceTimeBy(UNPAUSE_SETTLE * 2)

    assertEquals(emptyList(), follower.calls)
  }

  @Test
  fun followUploadNetwork_metered_pausesAtOnce() = runTest {
    val follower = Follower(this)

    follower.report(unmetered = false)

    assertEquals(listOf(true), follower.calls)
  }

  @Test
  fun followUploadNetwork_unmetered_resumesOnlyAfterTheSettleDelay() = runTest {
    val follower = Follower(this)
    follower.report(unmetered = false)

    follower.report(unmetered = true)
    advanceTimeBy(UNPAUSE_SETTLE - 1.milliseconds)
    runCurrent()
    assertEquals(listOf(true), follower.calls)
    advanceTimeBy(2.milliseconds)
    runCurrent()

    assertEquals(listOf(true, false), follower.calls)
  }

  @Test
  fun followUploadNetwork_flapShorterThanTheDelay_neverResumes() = runTest {
    val follower = Follower(this)
    follower.report(unmetered = false)

    follower.report(unmetered = true)
    advanceTimeBy(UNPAUSE_SETTLE / 2)
    follower.report(unmetered = false)
    advanceTimeBy(UNPAUSE_SETTLE * 2)
    runCurrent()

    assertEquals(listOf(true), follower.calls)
  }

  @Test
  fun followUploadNetwork_repeatedReportsAndOtherSettings_callNothingMore() = runTest {
    val follower = Follower(this)
    follower.report(unmetered = false)

    follower.report(unmetered = false)
    follower.settings.value = follower.settings.value.copy(upload = TorrentUploadMode.Seed)
    follower.settings.value = follower.settings.value.copy(trackers = listOf("udp://t.example/a"))
    advanceTimeBy(UNPAUSE_SETTLE * 2)
    runCurrent()

    assertEquals(listOf(true), follower.calls)
  }

  @Test
  fun followUploadNetwork_wifiOnlyTurnedOffWhileMetered_resumes() = runTest {
    val follower = Follower(this)
    follower.report(unmetered = false)

    follower.settings.value = follower.settings.value.copy(uploadWifiOnly = false)
    advanceTimeBy(UNPAUSE_SETTLE * 2)
    runCurrent()

    assertEquals(listOf(true, false), follower.calls)
  }

  @Test
  fun followUploadNetwork_pauseThrows_keepsFollowing() = runTest {
    val follower = Follower(this)
    follower.failing = true
    follower.report(unmetered = false)
    follower.failing = false

    follower.report(unmetered = true)
    advanceTimeBy(UNPAUSE_SETTLE * 2)
    runCurrent()
    follower.report(unmetered = false)

    assertEquals(listOf(false, true), follower.calls)
  }

  @Test
  fun followUploadNetwork_networkFails_pausesAndKeepsFollowingTheSettings() = runTest {
    val settings = MutableStateFlow(TorrentSettings(uploadWifiOnly = true))
    val calls = mutableListOf<Boolean>()
    val network = flow {
      emit(true)
      delay(UNPAUSE_SETTLE * 2)
      throw IllegalStateException("Connectivity service gone")
    }
    backgroundScope.launch { followUploadNetwork(settings, network) { calls += it } }

    advanceTimeBy(UNPAUSE_SETTLE * 3)
    runCurrent()
    assertEquals(listOf(false, true), calls)
    settings.value = settings.value.copy(uploadWifiOnly = false)
    advanceTimeBy(UNPAUSE_SETTLE * 2)
    runCurrent()

    assertEquals(listOf(false, true, false), calls)
  }
}
