package com.linroid.ketch.app.i18n

internal actual fun decimalSeparator(): Char = localeDecimalSeparator().firstOrNull() ?: '.'

/** What separates the decimals of 1.5 in the browser's locale. */
private fun localeDecimalSeparator(): String = js("(1.5).toLocaleString().replace(/[0-9]/g, '')")
