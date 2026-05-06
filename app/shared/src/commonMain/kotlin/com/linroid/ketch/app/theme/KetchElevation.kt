package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ShadowLayer(
  val offsetX: Dp,
  val offsetY: Dp,
  val blur: Dp,
  val spread: Dp,
  val color: Color,
)

@Immutable
data class KetchElevation(
  val level0: List<ShadowLayer> = emptyList(),
  val level1: List<ShadowLayer>,
  val level2: List<ShadowLayer>,
  val level3: List<ShadowLayer>,
  val level4: List<ShadowLayer>,
  val level5: List<ShadowLayer>,
) {
  val button: List<ShadowLayer> get() = level1
  val card: List<ShadowLayer> get() = level2
  val popover: List<ShadowLayer> get() = level3
  val dialog: List<ShadowLayer> get() = level4
  val window: List<ShadowLayer> get() = level5
}

fun lightKetchElevation(): KetchElevation = KetchElevation(
  level1 = listOf(
    ShadowLayer(0.dp, 1.dp, 2.dp, 0.dp, Color(0x14000000)),
    ShadowLayer(0.dp, 0.dp, 0.dp, (-0.5).dp, Color(0x14000000)),
  ),
  level2 = listOf(
    ShadowLayer(0.dp, 2.dp, 4.dp, 0.dp, Color(0x0F000000)),
    ShadowLayer(0.dp, 4.dp, 12.dp, 0.dp, Color(0x0A000000)),
  ),
  level3 = listOf(
    ShadowLayer(0.dp, 4.dp, 8.dp, 0.dp, Color(0x14000000)),
    ShadowLayer(0.dp, 8.dp, 24.dp, 0.dp, Color(0x0F000000)),
  ),
  level4 = listOf(
    ShadowLayer(0.dp, 12.dp, 16.dp, 0.dp, Color(0x1F000000)),
    ShadowLayer(0.dp, 24.dp, 48.dp, 0.dp, Color(0x14000000)),
  ),
  level5 = listOf(
    ShadowLayer(0.dp, 1.dp, 2.dp, 0.dp, Color(0x0A000000)),
    ShadowLayer(0.dp, 20.dp, 50.dp, 0.dp, Color(0x24000000)),
  ),
)

fun darkKetchElevation(): KetchElevation = KetchElevation(
  level1 = listOf(
    ShadowLayer(0.dp, 1.dp, 2.dp, 0.dp, Color(0x40000000)),
  ),
  level2 = listOf(
    ShadowLayer(0.dp, 2.dp, 4.dp, 0.dp, Color(0x33000000)),
    ShadowLayer(0.dp, 4.dp, 12.dp, 0.dp, Color(0x26000000)),
  ),
  level3 = listOf(
    ShadowLayer(0.dp, 4.dp, 8.dp, 0.dp, Color(0x40000000)),
    ShadowLayer(0.dp, 8.dp, 24.dp, 0.dp, Color(0x33000000)),
  ),
  level4 = listOf(
    ShadowLayer(0.dp, 12.dp, 16.dp, 0.dp, Color(0x59000000)),
    ShadowLayer(0.dp, 24.dp, 48.dp, 0.dp, Color(0x40000000)),
  ),
  level5 = listOf(
    ShadowLayer(0.dp, 20.dp, 50.dp, 0.dp, Color(0x66000000)),
  ),
)
