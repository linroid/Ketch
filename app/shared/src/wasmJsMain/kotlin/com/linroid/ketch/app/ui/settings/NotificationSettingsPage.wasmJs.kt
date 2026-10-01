@file:OptIn(ExperimentalWasmJsInterop::class)

package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.js.ExperimentalWasmJsInterop

@Composable
internal actual fun rememberNotificationPermission(): NotificationPermission? =
  remember { if (browserPermission() == UNSUPPORTED) null else BrowserNotificationPermission() }

/** The Notification API's permission, asked for from Settings. */
private class BrowserNotificationPermission : NotificationPermission {
  override var state: NotificationPermissionState by mutableStateOf(read())
    private set

  override fun request() {
    requestBrowserPermission { state = read() }
  }

  private fun read(): NotificationPermissionState = when (browserPermission()) {
    "granted" -> NotificationPermissionState.Granted
    "default" -> NotificationPermissionState.Undecided
    else -> NotificationPermissionState.Denied
  }
}

private const val UNSUPPORTED = "unsupported"

private fun browserPermission(): String =
  js("('Notification' in window) ? Notification.permission : 'unsupported'")

private fun requestBrowserPermission(onDone: () -> Unit): Unit = js(
  """{
  if (!('Notification' in window)) {
    onDone();
    return;
  }
  Notification.requestPermission().then(() => onDone(), () => onDone());
}"""
)
