package com.linroid.kdown.log

import android.util.Log

internal actual fun consoleLogger(): Logger = object : Logger {
  override fun v(message: () -> String) {
    Log.v("KDown", message())
  }

  override fun d(message: () -> String) {
    Log.d("KDown", message())
  }

  override fun i(message: () -> String) {
    Log.i("KDown", message())
  }

  override fun w(message: () -> String, throwable: Throwable?) {
    Log.w("KDown", message(), throwable)
  }

  override fun e(message: () -> String, throwable: Throwable?) {
    Log.e("KDown", message(), throwable)
  }
}
