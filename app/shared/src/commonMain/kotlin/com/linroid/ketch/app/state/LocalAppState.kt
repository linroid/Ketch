package com.linroid.ketch.app.state

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's [AppState], provided by the app shell, so composables deep in the tree can run task
 * commands through [AppState.runTaskCommand], post to [AppState.messages] and open the app's
 * surfaces without taking it as a parameter.
 */
val LocalAppState = staticCompositionLocalOf<AppState> {
  error("AppState not provided. Show the UI inside App or AppShell.")
}
