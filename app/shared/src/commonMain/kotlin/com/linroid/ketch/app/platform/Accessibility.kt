package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable

/**
 * Whether the system asks apps to reduce motion, following changes while the app runs.
 *
 * Android reads the animator duration scale, iOS the Reduce Motion setting, the web
 * `prefers-reduced-motion`, and macOS the accessibility preference (at startup and whenever
 * the window gains focus). Windows and Linux report `false`.
 */
@Composable
expect fun rememberReduceMotion(): Boolean
