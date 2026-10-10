package com.linroid.ketch.app.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps the system from sleeping on its own, such as a power assertion on desktop or a partial
 * wake lock on Android. The display can still turn off. [KeepAwake] calls it from one coroutine,
 * so calls never overlap.
 */
interface SleepInhibitor {
  /** Keeps the system awake until [release]; does nothing while it already does. */
  fun acquire()

  /** Lets the system sleep again; does nothing while it already may. */
  fun release()
}

/**
 * Keeps the system awake while the embedded device downloads, as the `[power] keepAwake` setting
 * allows. It follows the same busy signal as the Android download service ([ForegroundPolicy]),
 * so remote devices, which download on their own systems, never keep this one awake.
 */
object KeepAwake {
  /**
   * Holds [inhibitor] while the latest of [statuses] keeps the system awake
   * ([ForegroundStatus.keepsAwake]) and [enabled] says so; releases it once cancelled.
   */
  suspend fun follow(
    inhibitor: SleepInhibitor,
    statuses: Flow<ForegroundStatus>,
    enabled: Flow<Boolean>,
  ) {
    try {
      combine(statuses, enabled) { status, on -> on && status.keepsAwake }
        .distinctUntilChanged()
        .collect { hold -> if (hold) inhibitor.acquire() else inhibitor.release() }
    } finally {
      inhibitor.release()
    }
  }
}
