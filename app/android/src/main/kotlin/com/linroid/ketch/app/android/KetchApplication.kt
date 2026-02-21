package com.linroid.ketch.app.android

import android.app.Application
import android.content.Intent

class KetchApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    startForegroundService(Intent(this, KetchService::class.java))
  }
}
