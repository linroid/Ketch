package com.linroid.ketch.app.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword

internal actual val successFeedbackSupport: SuccessFeedbackSupport = SuccessFeedbackSupport.None

actual val isMobilePlatform: Boolean = false

@OptIn(ExperimentalComposeUiApi::class)
internal actual val HorizontalResizePointerIcon: PointerIcon = PointerIcon.fromKeyword("ew-resize")
