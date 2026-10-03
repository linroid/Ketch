package com.linroid.ketch.app.platform

import android.content.res.Resources

actual fun localDeviceKind(): LocalDeviceKind =
  if (Resources.getSystem().configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP) {
    LocalDeviceKind.Tablet
  } else {
    LocalDeviceKind.Phone
  }

/** Smallest width from which Android treats a screen as a tablet's. */
private const val TABLET_MIN_WIDTH_DP = 600
