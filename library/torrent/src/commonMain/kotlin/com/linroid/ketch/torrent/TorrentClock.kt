package com.linroid.ketch.torrent

import kotlin.time.TimeSource

internal fun monotonicClock(): () -> Long {
  val origin = TimeSource.Monotonic.markNow()
  return { origin.elapsedNow().inWholeMilliseconds }
}
