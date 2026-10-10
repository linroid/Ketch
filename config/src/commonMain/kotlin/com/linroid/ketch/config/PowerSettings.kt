package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * How the apps treat the power of the device they run on, persisted under `[power]`.
 *
 * @property keepAwake whether the desktop and Android apps keep the system from sleeping on its
 *   own while this device's downloads run or wait in the queue. The display can still turn off,
 *   and the user can still put the system to sleep.
 */
@Serializable
data class PowerSettings(
  val keepAwake: Boolean = true,
)
