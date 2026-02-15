package com.linroid.kdown.app.android

import android.app.Application
import android.content.Intent
import com.linroid.kdown.app.instance.AndroidNetworkEnvironment

class KDownApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    AndroidNetworkEnvironment.initialize(this)
    startForegroundService(Intent(this, KDownService::class.java))
  }
}
