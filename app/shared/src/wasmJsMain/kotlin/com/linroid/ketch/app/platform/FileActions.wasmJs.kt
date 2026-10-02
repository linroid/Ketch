package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable

/** The web app controls other devices only, whose files the browser cannot reach. */
@Composable
actual fun rememberFileActions(): FileActions? = null
