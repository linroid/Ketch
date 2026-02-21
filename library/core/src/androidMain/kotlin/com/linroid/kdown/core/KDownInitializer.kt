package com.linroid.kdown.core

import android.content.Context
import androidx.startup.Initializer

/**
 * AndroidX Startup [Initializer] that captures the application
 * [Context] at app launch. Registered automatically via manifest
 * merge — no user setup required.
 */
class KDownInitializer : Initializer<Unit> {
  override fun create(context: Context) {
    AndroidContext.set(context.applicationContext)
  }

  override fun dependencies(): List<Class<out Initializer<*>>> =
    emptyList()
}
