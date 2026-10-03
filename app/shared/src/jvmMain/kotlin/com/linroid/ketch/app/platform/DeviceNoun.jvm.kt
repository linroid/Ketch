package com.linroid.ketch.app.platform

actual fun localDeviceKind(): LocalDeviceKind = when (DesktopOs.current) {
  DesktopOs.MacOs -> LocalDeviceKind.Mac
  DesktopOs.Windows -> LocalDeviceKind.Pc
  DesktopOs.Linux -> LocalDeviceKind.Computer
}
