package com.linroid.ketch.app.platform

import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

actual fun localDeviceNoun(): String =
  if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
    "This iPad"
  } else {
    "This phone"
  }
