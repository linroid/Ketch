package com.linroid.ketch.app.snapshot

import com.linroid.ketch.app.state.AppState

// Scenarios from before the inspector followed the selection still ask for it to open; it shows
// with the task they inspect by itself now.
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
internal fun AppState.updateInspectorOpen(open: Boolean) = Unit
