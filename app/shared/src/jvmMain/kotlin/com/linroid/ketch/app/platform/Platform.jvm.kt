package com.linroid.ketch.app.platform

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

internal actual val successFeedbackSupport: SuccessFeedbackSupport = SuccessFeedbackSupport.Sound

actual val isMobilePlatform: Boolean = false

internal actual val HorizontalResizePointerIcon: PointerIcon =
  PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

internal actual val keepAwakeSupported: Boolean = true
