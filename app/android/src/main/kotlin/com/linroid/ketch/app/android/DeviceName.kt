package com.linroid.ketch.app.android

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The phone's name as Settings › About phone shows it, such as "Nothing Phone (4a)", which its
 * owner may have changed; its model number, such as "A069P", where the system keeps none.
 */
internal fun Context.deviceName(): String =
  Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
    ?.trim()
    ?.ifEmpty { null }
    ?: Build.MODEL
