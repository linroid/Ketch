package com.linroid.ketch.app.i18n

import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * iOS keeps the language in its per-app setting, Settings → Ketch → Language, which it offers
 * for the languages of the app's `.lproj` folders and applies by restarting the app.
 */
internal actual object PlatformLanguage {
  actual val systemKeepsChoice: Boolean = true

  actual val pickInApp: Boolean = false

  // The per-app setting is the AppleLanguages default of the app's own domain.
  actual fun systemChoice(): String? {
    val bundle = NSBundle.mainBundle.bundleIdentifier ?: return null
    val domain = NSUserDefaults.standardUserDefaults.persistentDomainForName(bundle)
    return (domain?.get(APPLE_LANGUAGES) as? List<*>)?.firstOrNull() as? String
  }

  actual fun apply(tag: String?) = Unit

  actual fun openSystemSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(
      url,
      options = emptyMap<Any?, Any>(),
      completionHandler = null,
    )
  }

  private const val APPLE_LANGUAGES = "AppleLanguages"
}
