package com.linroid.ketch.app.components

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.SpeedUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedLimitPickerTest {

  @Test
  fun parseSpeedInput_suffixes_readTheirUnit() {
    assertEquals(SpeedLimit.kbps(500), parseSpeedInput("500k", SpeedUnit.MB))
    assertEquals(SpeedLimit.mbps(2), parseSpeedInput("2m", SpeedUnit.KB))
    assertEquals(SpeedLimit.mbps(2), parseSpeedInput(" 2 MB/s ", SpeedUnit.KB))
    assertEquals(SpeedLimit.kbps(750), parseSpeedInput("750KB", SpeedUnit.MB))
  }

  @Test
  fun parseSpeedInput_decimals_areKeptToTheByte() {
    assertEquals(SpeedLimit.of(1_572_864), parseSpeedInput("1.5m", SpeedUnit.KB))
    assertEquals(SpeedLimit.of(1_572_864), parseSpeedInput("1,5m", SpeedUnit.KB))
  }

  @Test
  fun parseSpeedInput_bareNumber_usesTheDefaultUnit() {
    assertEquals(SpeedLimit.kbps(500), parseSpeedInput("500", SpeedUnit.KB))
    assertEquals(SpeedLimit.mbps(5), parseSpeedInput("5", SpeedUnit.MB))
  }

  @Test
  fun parseSpeedInput_unlimited_isUnlimited() {
    assertEquals(SpeedLimit.Unlimited, parseSpeedInput("Unlimited", SpeedUnit.MB))
  }

  @Test
  fun parseSpeedInput_zeroOrGarbage_isNull() {
    assertNull(parseSpeedInput("0", SpeedUnit.MB))
    assertNull(parseSpeedInput("", SpeedUnit.MB))
    assertNull(parseSpeedInput("fast", SpeedUnit.MB))
    assertNull(parseSpeedInput("-2m", SpeedUnit.MB))
  }

  @Test
  fun winningLimitCaption_lowerGlobalLimit_namesIt() {
    assertEquals(
      "Slow lane 1 MB/s applies to all downloads",
      winningLimitCaption(SpeedLimit.mbps(5), SpeedLimit.mbps(1), "Slow lane"),
    )
    assertEquals(
      "Slow lane 1 MB/s applies to all downloads",
      winningLimitCaption(SpeedLimit.Unlimited, SpeedLimit.mbps(1), "Slow lane"),
    )
  }

  @Test
  fun winningLimitCaption_ownLimitApplies_isNull() {
    assertNull(winningLimitCaption(SpeedLimit.kbps(512), SpeedLimit.mbps(1), "Slow lane"))
    assertNull(winningLimitCaption(SpeedLimit.mbps(1), SpeedLimit.mbps(1), "Slow lane"))
    assertNull(winningLimitCaption(SpeedLimit.mbps(5), SpeedLimit.Unlimited, "Slow lane"))
  }

  @Test
  fun debouncedCommit_valueTypedQuickly_commitsOnceAfterTheDelay() = runTest {
    val commits = mutableListOf<SpeedLimit>()
    val debounce = DebouncedCommit<SpeedLimit>(backgroundScope, 600.milliseconds) { commits += it }

    for (text in listOf("5", "50", "500")) {
      debounce.update(parseSpeedInput(text, SpeedUnit.KB)!!)
      advanceTimeBy(150.milliseconds)
    }
    assertEquals(emptyList(), commits)

    advanceUntilIdle()
    debounce.flush()

    assertEquals(listOf(SpeedLimit.kbps(500)), commits)
  }

  @Test
  fun debouncedCommit_flush_commitsAtOnceAndNotAgainLater() = runTest {
    val commits = mutableListOf<Int>()
    val debounce = DebouncedCommit<Int>(backgroundScope, 600.milliseconds) { commits += it }

    debounce.update(8)
    debounce.flush()
    advanceUntilIdle()

    assertEquals(listOf(8), commits)
  }

  @Test
  fun debouncedCommit_cancel_dropsTheWaitingValue() = runTest {
    val commits = mutableListOf<Int>()
    val debounce = DebouncedCommit<Int>(backgroundScope, 600.milliseconds) { commits += it }

    debounce.update(8)
    runCurrent()
    debounce.cancel()
    advanceUntilIdle()
    debounce.flush()

    assertEquals(emptyList(), commits)
  }
}
