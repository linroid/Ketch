package com.linroid.kdown.core.log

import android.util.Log

internal actual fun consoleLogger(minLevel: LogLevel): Logger =
  object : Logger {
    override fun v(message: () -> String) {
      if (minLevel <= LogLevel.VERBOSE) Log.v("KDown", message())
    }

    override fun d(message: () -> String) {
      if (minLevel <= LogLevel.DEBUG) Log.d("KDown", message())
    }

    override fun i(message: () -> String) {
      if (minLevel <= LogLevel.INFO) Log.i("KDown", message())
    }

    override fun w(message: () -> String, throwable: Throwable?) {
      if (minLevel <= LogLevel.WARN) Log.w("KDown", message(), throwable)
    }

    override fun e(message: () -> String, throwable: Throwable?) {
      if (minLevel <= LogLevel.ERROR) Log.e("KDown", message(), throwable)
    }
  }
