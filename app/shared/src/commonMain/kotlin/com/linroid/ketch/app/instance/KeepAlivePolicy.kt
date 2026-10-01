package com.linroid.ketch.app.instance

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Which remote devices [InstanceManager] keeps connected besides the active one, which is always
 * connected.
 *
 * @property maxWatched most watched devices kept connected; the first ones in the device list
 *   win, and the active device does not count.
 * @property backgroundGrace how long after the app goes to the background the other devices stay
 *   connected; they connect again when it returns. `null` keeps them connected.
 */
data class KeepAlivePolicy(
  val maxWatched: Int = 5,
  val backgroundGrace: Duration? = 10.minutes,
) {
  init {
    require(maxWatched >= 0) { "maxWatched must not be negative: $maxWatched" }
  }
}

/**
 * Whether the app is in the foreground, as the platform reports it on its own: `true` when it
 * returns, `false` when it leaves. Platforms whose host reports it through
 * [InstanceManager.setInForeground] emit nothing.
 */
internal expect fun appForegroundChanges(): Flow<Boolean>
