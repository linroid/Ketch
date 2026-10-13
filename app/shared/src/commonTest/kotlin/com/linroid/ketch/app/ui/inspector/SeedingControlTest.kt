package com.linroid.ketch.app.ui.inspector

import com.linroid.ketch.torrent.api.TorrentActivity
import com.linroid.ketch.torrent.api.TorrentRevision
import com.linroid.ketch.torrent.api.TorrentSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the inspector's seeding control offers for a finished torrent. */
class SeedingControlTest {
  private val stop = SeedingView(stop = true, status = false)
  private val stopWithStatus = SeedingView(stop = true, status = true)
  private val seed = SeedingView(stop = false, status = false)

  private fun snapshot(activity: TorrentActivity, seeding: Boolean?) = TorrentSnapshot(
    taskId = "t1",
    revision = TorrentRevision("epoch", 1),
    activity = activity,
    selectionGeneration = 0,
    selectionComplete = false,
    seeding = seeding,
  )

  @Test
  fun seedingView_seedingNow_offersStopWithStatus() {
    val snapshot = snapshot(TorrentActivity.SEEDING, seeding = true)

    assertEquals(stopWithStatus, seedingView(snapshot, sharing = true, canSeed = true))
    assertFalse(stopWithStatus.waiting)
  }

  @Test
  fun seedingView_intentWhileUploadsPaused_offersStopWhileWaiting() {
    val snapshot = snapshot(TorrentActivity.STOPPED, seeding = true)

    assertEquals(stop, seedingView(snapshot, sharing = false, canSeed = true))
    // "Waiting to seed" in place of the status, which needs a seeding session.
    assertTrue(stop.waiting)
    // The intent can be cleared even where the device cannot seed now.
    assertEquals(stop, seedingView(snapshot, sharing = false, canSeed = false))
  }

  @Test
  fun seedingView_notMeantToSeed_offersSeedWhileTheDeviceCan() {
    val snapshot = snapshot(TorrentActivity.STOPPED, seeding = false)

    assertEquals(seed, seedingView(snapshot, sharing = false, canSeed = true))
    assertFalse(seed.waiting)
    assertNull(seedingView(snapshot, sharing = false, canSeed = false))
  }

  @Test
  fun seedingView_backendWithoutIntent_followsTheActivity() {
    val seeding = snapshot(TorrentActivity.SEEDING, seeding = null)
    val stopped = snapshot(TorrentActivity.STOPPED, seeding = null)

    assertEquals(stopWithStatus, seedingView(seeding, sharing = false, canSeed = false))
    // The snapshot is newer than the task's state.
    assertEquals(seed, seedingView(stopped, sharing = true, canSeed = true))
  }

  @Test
  fun seedingView_beforeTheFirstSnapshot_followsTheTaskState() {
    assertEquals(stopWithStatus, seedingView(null, sharing = true, canSeed = false))
    assertEquals(seed, seedingView(null, sharing = false, canSeed = true))
    assertNull(seedingView(null, sharing = false, canSeed = false))
  }
}
