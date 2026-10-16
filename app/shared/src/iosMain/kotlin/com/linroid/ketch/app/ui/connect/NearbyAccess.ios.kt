package com.linroid.ketch.app.ui.connect

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberNearbyAccess(): NearbyAccess = NearbyAccess { _, onDone -> onDone() }
