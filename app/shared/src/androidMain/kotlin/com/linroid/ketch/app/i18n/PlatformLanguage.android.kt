package com.linroid.ketch.app.i18n

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Android 13 and later keep the language in their per-app setting, through [LocaleManager],
 * which also applies it to the app's processes and activities. Earlier versions have none, so
 * the app keeps it in config.toml and sets it as the process's default locale itself.
 */
internal actual object PlatformLanguage {
  private var context: Context? = null

  // The app's chosen language before Android 13; null follows the system.
  private var chosen: Locale? = null

  // The process's own language, before the app set one.
  private val system: Locale = Locale.getDefault()

  // The app's live activities before Android 13, recreated in a new language.
  private val activities = mutableSetOf<Activity>()

  actual val systemKeepsChoice: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  actual val pickInApp: Boolean = true

  actual fun systemChoice(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val manager = context?.getSystemService(LocaleManager::class.java) ?: return null
    return manager.applicationLocales.toLanguageTags().substringBefore(',').ifEmpty { null }
  }

  actual fun apply(tag: String?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context?.getSystemService(LocaleManager::class.java)?.applicationLocales =
        LocaleList.forLanguageTags(tag.orEmpty())
      return
    }
    val locale = tag?.let(Locale::forLanguageTag)
    val changed = locale != chosen
    chosen = locale
    reapply()
    // An activity reads the language it was created with, so a new one recreates them.
    if (changed) activities.toList().forEach(Activity::recreate)
  }

  actual fun openSystemSettings() = Unit

  fun init(context: Context, saved: String?) {
    this.context = context.applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      moveIntoSystem(saved)
      return
    }
    (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(Tracker)
    apply(saved)
  }

  // A language saved before Android 13, on the first launch after the device updated, moves into
  // the per-app setting while that is empty; AppSettingsController then clears it from the config.
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun moveIntoSystem(saved: String?) {
    val manager = context?.getSystemService(LocaleManager::class.java) ?: return
    if (saved != null && manager.applicationLocales.isEmpty) {
      manager.applicationLocales = LocaleList.forLanguageTags(saved)
    }
  }

  // Keeps [activities] to the ones alive.
  private object Tracker : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
      activities += activity
    }

    override fun onActivityDestroyed(activity: Activity) {
      activities -= activity
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  }

  /**
   * Sets the chosen language as the process's default again, which Android resets to the
   * system's when the configuration changes; before Android 13 only.
   */
  fun reapply() {
    if (systemKeepsChoice) return
    val locales = LocaleList(chosen ?: system)
    LocaleList.setDefault(locales)
    val resources = context?.resources ?: return
    val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
    @Suppress("DEPRECATION")
    resources.updateConfiguration(configuration, resources.displayMetrics)
  }

  fun wrap(base: Context): Context {
    val locale = chosen
    if (systemKeepsChoice || locale == null) return base
    val configuration = Configuration(base.resources.configuration)
      .apply { setLocales(LocaleList(locale)) }
    return base.createConfigurationContext(configuration)
  }
}

/**
 * Sets up the app's language once the app starts: Android 13 and later keep it in their per-app
 * setting; earlier versions show the app in [saved], the language in config.toml.
 */
fun initAppLanguage(context: Context, saved: String?) = PlatformLanguage.init(context, saved)

/**
 * [base] reading its resources in the app's language, for an activity's `attachBaseContext`;
 * Android 13 and later do this themselves. Also sets the language as the process's default
 * again, which Android resets when the configuration changes.
 */
fun appLanguageContext(base: Context): Context {
  PlatformLanguage.reapply()
  return PlatformLanguage.wrap(base)
}
