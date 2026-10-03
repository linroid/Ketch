package com.linroid.ketch.app.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.linroid.ketch.app.components.DeviceType
import com.linroid.ketch.app.components.LocalDeviceTypes
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.LocalDeviceKind
import com.linroid.ketch.app.platform.localDeviceKind
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId

/**
 * What kind of device [entry] is: the form factor of the embedded one, which is a [localKind],
 * and for a remote one what the system it last reported suggests; `null` for a remote device
 * that has not connected yet.
 */
internal fun deviceType(entry: InstanceEntry, localKind: LocalDeviceKind): DeviceType? =
  when (entry) {
    is EmbeddedInstance -> when (localKind) {
      LocalDeviceKind.Phone -> DeviceType.Phone
      LocalDeviceKind.IPad, LocalDeviceKind.Tablet -> DeviceType.Tablet
      LocalDeviceKind.Browser -> DeviceType.Browser
      LocalDeviceKind.Mac -> DeviceType.Laptop
      LocalDeviceKind.Pc, LocalDeviceKind.Computer -> DeviceType.Desktop
    }
    is RemoteInstance -> entry.remoteConfig.os?.let(::systemDeviceType)
    else -> null
  }

/**
 * The kind of device that runs [os], a system as devices report it ("Mac OS X", "Windows 11",
 * "Android 15", "iOS 18.0"): Android and iOS run phones, while Linux and systems not listed here
 * mostly run servers; `null` for a blank name.
 */
internal fun systemDeviceType(os: String): DeviceType? {
  val name = os.trim()
  return when {
    name.isEmpty() -> null
    name.startsWith("mac", ignoreCase = true) || name.startsWith("darwin", ignoreCase = true) ->
      DeviceType.Laptop
    name.startsWith("windows", ignoreCase = true) -> DeviceType.Desktop
    name.startsWith("ipados", ignoreCase = true) -> DeviceType.Tablet
    name.startsWith("android", ignoreCase = true) || name.startsWith("ios", ignoreCase = true) ->
      DeviceType.Phone
    else -> DeviceType.Server
  }
}

/** The [DeviceType] of each device of [state] it knows, by device id, for [LocalDeviceTypes]. */
@Composable
fun rememberDeviceTypes(state: AppState): Map<String, DeviceType> {
  val instances by state.instances.collectAsState()
  return remember(instances) {
    val localKind = localDeviceKind()
    instances
      .mapNotNull { entry -> deviceType(entry, localKind)?.let { entry.deviceId to it } }
      .toMap()
  }
}
