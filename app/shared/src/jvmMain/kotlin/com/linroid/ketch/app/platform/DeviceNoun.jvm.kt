package com.linroid.ketch.app.platform

/**
 * The kind of device [localDeviceKind] reports instead of this computer's, for snapshots that
 * render the app as it looks on a phone or a tablet; `null` reports this computer.
 */
@Volatile
internal var localDeviceKindOverride: LocalDeviceKind? = null

actual fun localDeviceKind(): LocalDeviceKind =
  localDeviceKindOverride ?: when (DesktopOs.current) {
    DesktopOs.MacOs -> LocalDeviceKind.Mac
    DesktopOs.Windows -> LocalDeviceKind.Pc
    DesktopOs.Linux -> LocalDeviceKind.Computer
  }
