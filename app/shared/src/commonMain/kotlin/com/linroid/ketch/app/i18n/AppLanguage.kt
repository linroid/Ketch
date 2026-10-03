package com.linroid.ketch.app.i18n

import androidx.compose.runtime.Immutable

/**
 * A language the app is translated to: its BCP 47 [tag], as Settings stores it, and its [name] in
 * itself, such as "Deutsch", which Settings lists whatever language the app shows.
 */
@Immutable
data class AppLanguage(val tag: String, val name: UiText)

/**
 * The languages the app is translated to, in the order Settings lists them: English, then the
 * translations of `composeResources/values-*` (docs/development/localization.md).
 */
val AppLanguages: List<AppLanguage> = listOf(
  AppLanguage("en", verbatim("English")),
  AppLanguage("zh-Hans", verbatim("简体中文")),
  AppLanguage("zh-Hant", verbatim("繁體中文")),
  AppLanguage("ja", verbatim("日本語")),
  AppLanguage("ko", verbatim("한국어")),
  AppLanguage("es", verbatim("Español")),
  AppLanguage("pt-BR", verbatim("Português (Brasil)")),
  AppLanguage("de", verbatim("Deutsch")),
  AppLanguage("fr", verbatim("Français")),
  AppLanguage("ru", verbatim("Русский")),
)

/**
 * The translation a system language reads, such as zh-Hant for "zh-TW" or pt-BR for "pt-PT", or
 * `null` when the app has none and shows English. Chinese without a script reads Traditional in
 * Taiwan, Hong Kong and Macau.
 */
fun appLanguageOf(tag: String?): AppLanguage? {
  val subtags = tag?.replace('_', '-')?.split('-')?.filter { it.isNotEmpty() } ?: return null
  val language = subtags.firstOrNull()?.lowercase() ?: return null
  val script = subtags.drop(1).firstOrNull { it.length == 4 }?.lowercase()
  val region = subtags.drop(1).firstOrNull { it.length == 2 }?.uppercase()
  if (language == "zh") {
    val traditional = script == "hant" || script == null && region in TRADITIONAL_REGIONS
    return AppLanguages.first { it.tag == if (traditional) "zh-Hant" else "zh-Hans" }
  }
  return AppLanguages.firstOrNull { it.tag.substringBefore('-') == language }
}

// Regions whose Chinese is written in Traditional characters.
private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

/**
 * Where the platform keeps the language the app shows. Android 13 and later and iOS keep it in
 * their per-app language setting, so a choice made in the system's Settings shows in the app's
 * too; elsewhere the app keeps it in `config.toml` and applies it itself.
 */
internal expect object PlatformLanguage {
  /** Whether the system keeps the choice, rather than the app's config. */
  val systemKeepsChoice: Boolean

  /** Whether Settings lists the languages to pick from; on iOS it opens the system's Settings. */
  val pickInApp: Boolean

  /** The language chosen in the system's per-app setting, or `null` while it follows the system. */
  fun systemChoice(): String?

  /** Shows the app in [tag], one of [AppLanguages], or in the system's language for `null`. */
  fun apply(tag: String?)

  /** Opens the system's settings for the app, where it chooses the app's language. */
  fun openSystemSettings()
}
