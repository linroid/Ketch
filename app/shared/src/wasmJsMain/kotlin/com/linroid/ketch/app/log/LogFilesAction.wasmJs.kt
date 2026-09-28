package com.linroid.ketch.app.log

import androidx.compose.runtime.Composable

/** The web app logs to the browser console only. */
@Composable
internal actual fun rememberLogFilesAction(logger: FileLogger): LogFilesAction? = null
