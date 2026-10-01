package com.linroid.ketch.app.platform

actual fun localDeviceNoun(): String = when (DesktopOs.current) {
  DesktopOs.MacOs -> "This Mac"
  DesktopOs.Windows -> "This PC"
  DesktopOs.Linux -> "This computer"
}
