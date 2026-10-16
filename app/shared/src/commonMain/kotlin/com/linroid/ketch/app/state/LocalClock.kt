package com.linroid.ketch.app.state

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * The clock composables read the current time from. The app shell provides [AppState.clock], so
 * every time label agrees with the task list and the speed history.
 */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.System }

/**
 * What composables measure elapsed time with, such as how long a toast has shown. The app shell
 * provides [AppState.timeSource], so they keep time with the connection rates.
 */
val LocalTimeSource = staticCompositionLocalOf<TimeSource.WithComparableMarks> {
  TimeSource.Monotonic
}
