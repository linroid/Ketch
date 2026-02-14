package com.linroid.kdown.app.android

import android.app.Application
import android.content.Intent

class KDownApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    startForegroundService(Intent(this, KDownService::class.java))
  }
}
