package com.linroid.ketch.app.input

import kotlinx.browser.window

internal actual fun detectKeyboardPlatform(): KeyboardPlatform {
  val agent = window.navigator.userAgent
  val apple = listOf("Mac", "iPhone", "iPad").any { it in agent }
  return if (apple) KeyboardPlatform.WebMac else KeyboardPlatform.WebPc
}
