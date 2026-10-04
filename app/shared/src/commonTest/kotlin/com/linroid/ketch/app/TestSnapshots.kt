package com.linroid.ketch.app

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Sends the apply notifications of the state written so far, as a frame would, and returns once
 * every observer has them, so a `snapshotFlow` collector is ready to run.
 *
 * Another thread may send them first: any thread that sends apply notifications or applies a
 * snapshot, such as a scene of a UI test or work one left running in the same process, delivers
 * every change written so far. [Snapshot.sendApplyNotifications] then finds nothing to send and
 * returns while that thread is still calling the observers, and a collector would see the change
 * only after the test has looked.
 */
fun applySnapshotChanges() {
  Snapshot.sendApplyNotifications()
  val start = TimeSource.Monotonic.markNow()
  while (Snapshot.isApplyObserverNotificationPending) {
    check(start.elapsedNow() < APPLY_TIMEOUT) { "Apply observers never finished" }
  }
}

/**
 * Runs what is due, applies the state it wrote (see [applySnapshotChanges]) and runs what that
 * woke, as a frame would.
 */
fun TestScope.settleSnapshots() {
  runCurrent()
  applySnapshotChanges()
  runCurrent()
}

private val APPLY_TIMEOUT = 10.seconds
