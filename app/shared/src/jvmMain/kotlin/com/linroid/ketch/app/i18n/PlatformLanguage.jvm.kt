package com.linroid.ketch.app.i18n

import java.util.Locale

/** The desktop keeps the language in config.toml and sets it as the JVM's default locale. */
internal actual object PlatformLanguage {
  // The JVM's own language, before the app set one.
  private val system: Locale = Locale.getDefault()

  actual val systemKeepsChoice: Boolean = false

  actual val pickInApp: Boolean = true

  actual fun systemChoice(): String? = null

  actual fun apply(tag: String?) {
    Locale.setDefault(tag?.let(Locale::forLanguageTag) ?: system)
  }

  actual fun openSystemSettings() = Unit
}
