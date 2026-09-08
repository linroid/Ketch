package com.linroid.ketch.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TorrentConnectTest {
  @Test
  fun unreachableFirstAddressLeavesTimeForFallback() = runTest {
    val attempted = mutableListOf<Int>()
    val closed = mutableListOf<Int>()
    val result = connectTorrentCandidates(listOf(6, 4)) { family ->
      attempted += family
      if (family == 6) {
        try {
          delay(20_000)
        } finally {
          closed += family
        }
      }
      family
    }
    assertEquals(4, result)
    assertEquals(listOf(6, 4), attempted)
    assertEquals(listOf(6), closed)
    assertEquals(5_000L, testScheduler.currentTime)
  }
}
