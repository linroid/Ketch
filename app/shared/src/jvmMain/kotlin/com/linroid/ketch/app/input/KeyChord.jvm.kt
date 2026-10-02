package com.linroid.ketch.app.input

internal actual fun detectKeyboardPlatform(): KeyboardPlatform =
  if (System.getProperty("os.name", "").startsWith("Mac")) {
    KeyboardPlatform.Mac
  } else {
    KeyboardPlatform.Pc
  }
