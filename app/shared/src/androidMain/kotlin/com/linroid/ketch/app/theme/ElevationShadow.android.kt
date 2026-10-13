package com.linroid.ketch.app.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow

internal actual fun Modifier.elevationShadow(shape: Shape, shadow: Shadow): Modifier =
  dropShadow(shape, shadow)
