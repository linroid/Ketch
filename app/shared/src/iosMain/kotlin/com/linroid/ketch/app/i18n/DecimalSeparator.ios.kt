package com.linroid.ketch.app.i18n

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator

internal actual fun decimalSeparator(): Char =
  NSLocale.currentLocale.decimalSeparator.firstOrNull() ?: '.'
