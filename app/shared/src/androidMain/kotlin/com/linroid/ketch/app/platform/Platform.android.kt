package com.linroid.ketch.app.platform

import androidx.compose.ui.input.pointer.PointerIcon

internal actual val successFeedbackSupport: SuccessFeedbackSupport = SuccessFeedbackSupport.SoundAndVibration

actual val isMobilePlatform: Boolean = true

internal actual val HorizontalResizePointerIcon: PointerIcon =
  PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
