package com.linroid.kdown.app.instance

import android.content.Context
import android.net.wifi.WifiManager

object AndroidNetworkEnvironment {
  @Volatile
  private var appContext: Context? = null

  fun initialize(context: Context) {
    appContext = context.applicationContext
  }

  fun <T> withMulticastLock(block: () -> T): T {
    val context = appContext ?: return block()
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE)
      as? WifiManager ?: return block()

    val lock = wifiManager.createMulticastLock("kdown-mdns")
    lock.setReferenceCounted(false)
    runCatching { lock.acquire() }

    return try {
      block()
    } finally {
      if (lock.isHeld) {
        runCatching { lock.release() }
      }
    }
  }
}
