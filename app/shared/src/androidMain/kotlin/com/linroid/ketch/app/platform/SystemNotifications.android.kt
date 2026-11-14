package com.linroid.ketch.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.OngoingPresentation

/**
 * Android's notification settings for this app, opened from [context].
 */
class AndroidSystemNotifications(private val context: Context) : SystemNotifications {
  private val log = KetchLogger("SystemNotifications")

  override val liveUpdates: Boolean =
    Build.VERSION.SDK_INT >= OngoingPresentation.LIVE_UPDATES_SDK

  override fun openSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      log.w { "Couldn't open the system's notification settings: ${e.describeCauses()}" }
    }
  }
}
