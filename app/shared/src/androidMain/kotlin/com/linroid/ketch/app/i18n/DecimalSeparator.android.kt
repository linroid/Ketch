package com.linroid.ketch.app.i18n

import java.text.DecimalFormatSymbols
import java.util.Locale

internal actual fun decimalSeparator(): Char =
  DecimalFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT)).decimalSeparator
