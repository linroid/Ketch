package com.linroid.ketch.app.instance

import com.linroid.ketch.app.state.PulseScope

/** Which devices the app shows: one, or every device at once. */
sealed interface DeviceScope {
  /** Whether the device with [deviceId] is in this scope. */
  fun includes(deviceId: String): Boolean

  /** One device, the active one. */
  data class Single(val deviceId: String) : DeviceScope {
    override fun includes(deviceId: String): Boolean = deviceId == this.deviceId
  }

  /** Every device; offered from [MIN_DEVICES] devices on. */
  data object All : DeviceScope {
    override fun includes(deviceId: String): Boolean = true
  }

  companion object {
    /** Fewest devices for which [All] is offered. */
    const val MIN_DEVICES: Int = 2
  }
}

/** The scope the Pulse bar sums up for this one. */
fun DeviceScope.toPulseScope(): PulseScope = when (this) {
  is DeviceScope.Single -> PulseScope.Device(deviceId)
  DeviceScope.All -> PulseScope.AllDevices
}
