package com.linroid.ketch.app.i18n

internal actual fun decimalSeparator(): Char = localeDecimalSeparator().firstOrNull() ?: '.'

/**
 * What separates the decimals of 1.5 in the browser's locale, as its number format names it, so
 * locales that write other digits, such as Arabic's "١٫٥", give their separator too.
 */
private fun localeDecimalSeparator(): String = js(
  "(new Intl.NumberFormat().formatToParts(1.5).find((p) => p.type === 'decimal') || {}).value || ''"
)
