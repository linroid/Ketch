package com.linroid.ketch.torrent.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A v2 download's speed, sampled every 200 ms as its task reports progress, on a test clock. */
class TorrentV2SpeedTest {
  @Test
  fun sample_blockEveryThreeSeconds_readsNearTheLimit() {
    // Under a 5 KiB/s limit, paid before each 16 KiB block is asked for, a block arrives about
    // every 3.2 s, some sooner and some later as peers answer.
    val arrivals = (1..20).associate { (it * 3_200L + if (it % 2 == 0) 200 else -200) to it }
    val speed = TorrentV2Speed()
    var received = 0L
    for (now in 0L..64_000L step 200) {
      arrivals[now]?.let { received = it * 16_384L }
      val reading = speed.sample(now, received)
      // Once a few blocks arrived, it reads within a tenth of the limit, never a block's worth
      // in one second and none in the next.
      if (now >= 16_000) {
        assertTrue(reading in 4_608L..5_632L, "Read $reading B/s at $now ms")
      }
    }
  }

  @Test
  fun sample_steadyStream_readsItsRateAtOnce() {
    val speed = TorrentV2Speed()
    assertEquals(0, speed.sample(0, 0))
    // 100 KiB every 200 ms.
    for (step in 1..10) assertEquals(512_000L, speed.sample(step * 200L, step * 102_400L))
  }

  @Test
  fun sample_payloadStops_fallsThenReadsZero() {
    val speed = TorrentV2Speed()
    for (step in 0..10) speed.sample(step * 200L, step * 102_400L)
    // A second without payload, five times the usual gap: lower, not yet none.
    val falling = speed.sample(3_000, 1_024_000)
    assertTrue(falling in 1L..<512_000L, "Read $falling B/s")
    // Over two seconds without payload: none.
    assertEquals(0, speed.sample(4_100, 1_024_000))
  }
}
