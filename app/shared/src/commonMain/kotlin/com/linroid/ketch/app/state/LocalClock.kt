package com.linroid.ketch.app.state

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.Clock

/**
 * The clock composables read the current time from. The app shell provides [AppState.clock], so
 * every time label agrees with the task list and the speed history.
 */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.System }
