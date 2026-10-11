package com.linroid.ketch.app.state

import com.linroid.ketch.api.Interest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How often the segments of the rows on screen update, for their lane strips. */
internal val ShownSegmentsInterval: Duration = 1.seconds

/** How often the segments of the inspected tasks update. */
internal val InspectedSegmentsInterval: Duration = 500.milliseconds

/**
 * Follows the segments of the tasks of [keys] on their devices every [interval] until the caller
 * is cancelled, following each device to the client it reaches it through now: a device that
 * connects again does so with a new client. Remote devices send a task's segments only while
 * they are followed; the embedded engine has them live anyway.
 */
internal suspend fun AppState.followSegments(keys: Set<TaskKey>, interval: Duration): Nothing {
  val byDevice = keys.groupBy({ it.deviceId }, { it.taskId })
  instances
    .map { entries -> entries.filter { it.deviceId in byDevice } }
    .distinctUntilChanged { old, new -> old.map { it.instance } == new.map { it.instance } }
    .collectLatest { entries ->
      coroutineScope {
        for (entry in entries) {
          val segments = Interest.Segments(byDevice.getValue(entry.deviceId).toSet())
          launch { entry.instance.subscribe(segments, interval).collect {} }
        }
      }
    }
  awaitCancellation()
}
