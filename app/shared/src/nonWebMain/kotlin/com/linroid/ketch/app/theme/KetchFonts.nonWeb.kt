package com.linroid.ketch.app.theme

import androidx.compose.runtime.Composable

// Compose resources load fonts synchronously here.
@Composable
internal actual fun rememberFontsPreloaded(fonts: List<KetchFont>): Boolean = true
