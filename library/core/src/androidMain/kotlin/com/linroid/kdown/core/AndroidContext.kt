package com.linroid.kdown.core

import android.annotation.SuppressLint
import android.content.Context

/**
 * Internal holder for the Android application [Context].
 *
 * Automatically initialized by [KDownInitializer] via AndroidX Startup.
 */
@SuppressLint("StaticFieldLeak")
internal object AndroidContext {
  private var ctx: Context? = null

  fun set(context: Context) {
    ctx = context.applicationContext
  }

  fun get(): Context = ctx
    ?: error(
      "KDown Android context not initialized. " +
        "Ensure androidx.startup is not disabled."
    )
}
