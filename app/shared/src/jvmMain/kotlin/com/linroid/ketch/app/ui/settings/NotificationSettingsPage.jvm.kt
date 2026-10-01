package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable

// The host asks for the permission itself, at the first add.
@Composable
internal actual fun rememberNotificationPermission(): NotificationPermission? = null
