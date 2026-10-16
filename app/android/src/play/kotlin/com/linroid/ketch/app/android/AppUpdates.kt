package com.linroid.ketch.app.android

import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.state.AppController
import kotlinx.coroutines.CoroutineScope

/** Google Play owns updates for this distribution, even when its APK is sideloaded. */
@Suppress("UNUSED_PARAMETER")
internal fun createAppUpdates(
  scope: CoroutineScope,
  app: KetchApplication,
  controller: AppController,
): AppUpdates? = null
