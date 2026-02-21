package com.linroid.ketch.core.log

import android.util.Log

internal actual fun consoleLogger(minLevel: LogLevel): Logger =
  object : Logger {
    override fun v(message: String) {
      if (minLevel <= LogLevel.VERBOSE) Log.v("Ketch", message)
    }

    override fun d(message: String) {
      if (minLevel <= LogLevel.DEBUG) Log.d("Ketch", message)
    }

    override fun i(message: String) {
      if (minLevel <= LogLevel.INFO) Log.i("Ketch", message)
    }

    override fun w(message: String, throwable: Throwable?) {
      if (minLevel <= LogLevel.WARN) Log.w("Ketch", message, throwable)
    }

    override fun e(message: String, throwable: Throwable?) {
      if (minLevel <= LogLevel.ERROR) Log.e("Ketch", message, throwable)
    }
  }
