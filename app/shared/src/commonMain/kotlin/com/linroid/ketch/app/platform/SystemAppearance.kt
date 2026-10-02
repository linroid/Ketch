package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable

/**
 * Makes what the system draws over the app follow the appearance picked in Settings: dark when
 * [dark] is `true`, light when `false`, and the system's own when `null`.
 *
 * Android colors the icons of the status and navigation bars, and iOS sets the interface style of
 * the app's window, which the status bar and system sheets follow. The desktop app sets its
 * window's appearance itself, and the web has nothing to follow it.
 */
@Composable
expect fun SystemAppearance(dark: Boolean?)
