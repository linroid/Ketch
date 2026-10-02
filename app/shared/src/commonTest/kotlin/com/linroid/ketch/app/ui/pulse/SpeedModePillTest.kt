package com.linroid.ketch.app.ui.pulse

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedModePillTest {

  @Test
  fun pendingJob_commandEnds_isNoLongerPending() {
    val command = PendingJob()
    val job = Job()

    command.track(job)
    assertTrue(command.pending)
    job.complete()

    assertFalse(command.pending)
  }

  @Test
  fun pendingJob_noCommandStarted_keepsTrackingTheLastOne() {
    val command = PendingJob()
    val job = Job()

    command.track(job)
    command.track(null)

    assertTrue(command.pending)
  }

  @Test
  fun pendingJob_olderCommandEnds_stillTracksTheNewerOne() {
    val command = PendingJob()
    val older = Job()
    val newer = Job()

    command.track(older)
    command.track(newer)
    older.complete()

    assertTrue(command.pending)
  }
}
