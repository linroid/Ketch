package com.linroid.ketch.app.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.app.state.SleepInhibitor

/**
 * A partial wake lock, which keeps the CPU running while the screen is off so downloads keep
 * going. Android lets go of it when the process ends.
 */
internal class WakeLockInhibitor(context: Context) : SleepInhibitor {
  private val log = KetchLogger("KeepAwake")
  private val wakeLock = context.getSystemService(PowerManager::class.java)
    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
    .apply { setReferenceCounted(false) }

  // Downloads take as long as they take, so there is no timeout to give: KeepAwake releases the
  // lock once they stop, the service leaves the foreground or the service ends.
  @SuppressLint("WakelockTimeout")
  override fun acquire() {
    if (wakeLock.isHeld) return
    wakeLock.acquire()
    log.i { "Holding a wake lock while downloading" }
  }

  override fun release() {
    if (!wakeLock.isHeld) return
    wakeLock.release()
    log.i { "Released the wake lock" }
  }

  private companion object {
    const val TAG = "Ketch:downloads"
  }
}
