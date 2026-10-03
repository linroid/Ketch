package com.linroid.ketch.app.platform

import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

actual fun localDeviceKind(): LocalDeviceKind =
  if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
    LocalDeviceKind.IPad
  } else {
    LocalDeviceKind.Phone
  }
