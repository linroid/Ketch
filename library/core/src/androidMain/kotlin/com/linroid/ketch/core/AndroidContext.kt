package com.linroid.ketch.core

import android.annotation.SuppressLint
import android.content.Context

/**
 * Internal holder for the Android application [Context].
 *
 * Automatically initialized by [KetchInitializer] via AndroidX Startup.
 */
@SuppressLint("StaticFieldLeak")
internal object AndroidContext {
  private var ctx: Context? = null

  fun set(context: Context) {
    ctx = context.applicationContext
  }

  fun get(): Context = ctx
    ?: error(
      "Ketch Android context not initialized. " +
        "Ensure androidx.startup is not disabled."
    )
}
