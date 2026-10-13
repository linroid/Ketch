package com.linroid.ketch.app.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow

/**
 * Draws [shadow] of [shape] behind the content, as `Modifier.dropShadow` does.
 *
 * `dropShadow` blurs every shadow into a bitmap the size of the shape on the CPU and draws it as
 * an image, building a new one each time the size changes. On Skia (desktop, iOS and the web)
 * a resize or an animated sheet left one bitmap and one GPU texture per size behind, several
 * megabytes each for a window-sized card, until a collection freed them. There the shadow is
 * drawn as a blurred shape instead, which Skia draws on the GPU from a small cached blur
 * profile whatever the size. Android keeps `dropShadow`: its hardware canvas ignores blur mask
 * filters before API 28.
 */
internal expect fun Modifier.elevationShadow(shape: Shape, shadow: Shadow): Modifier
