@file:OptIn(ExperimentalWasmJsInterop::class)

package com.linroid.ketch.app.i18n

import kotlin.js.ExperimentalWasmJsInterop

/**
 * The web app keeps the language in its config and has the page report it as the browser's
 * language, which Compose reads.
 */
internal actual object PlatformLanguage {
  actual val systemKeepsChoice: Boolean = false

  actual val pickInApp: Boolean = true

  actual fun systemChoice(): String? = null

  actual fun apply(tag: String?) = setBrowserLanguage(tag)

  actual fun openSystemSettings() = Unit
}

// Has navigator.language and navigator.languages report tag, or the browser's own for null.
private fun setBrowserLanguage(tag: String?): Unit = js(
  """{
  if (!window.__ketchLanguageInstalled) {
    window.__ketchLanguageInstalled = true;
    const languages = Object.getOwnPropertyDescriptor(Navigator.prototype, 'languages');
    const language = Object.getOwnPropertyDescriptor(Navigator.prototype, 'language');
    Object.defineProperty(Navigator.prototype, 'languages', {
      configurable: true,
      get() {
        return window.__ketchLanguage ? [window.__ketchLanguage] : languages.get.call(this);
      },
    });
    Object.defineProperty(Navigator.prototype, 'language', {
      configurable: true,
      get() { return window.__ketchLanguage || language.get.call(this); },
    });
  }
  window.__ketchLanguage = tag;
}"""
)
