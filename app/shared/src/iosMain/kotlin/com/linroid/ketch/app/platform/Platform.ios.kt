package com.linroid.ketch.app.platform

import androidx.compose.ui.input.pointer.PointerIcon

internal actual val successFeedbackSupport: SuccessFeedbackSupport = SuccessFeedbackSupport.None

actual val isMobilePlatform: Boolean = true

internal actual val HorizontalResizePointerIcon: PointerIcon = PointerIcon.Default

internal actual val keepAwakeSupported: Boolean = false
