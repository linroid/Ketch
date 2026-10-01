package com.linroid.ketch.app.platform

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual val isMobilePlatform: Boolean = false

internal actual val HorizontalResizePointerIcon: PointerIcon =
  PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
