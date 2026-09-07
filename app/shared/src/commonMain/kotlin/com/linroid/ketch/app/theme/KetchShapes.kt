package com.linroid.ketch.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Immutable
data class KetchShapes(
  val xs: Shape = RoundedCornerShape(10.dp),
  val sm: Shape = RoundedCornerShape(10.dp),
  val md: Shape = RoundedCornerShape(8.dp),
  val lg: Shape = RoundedCornerShape(10.dp),
  val xl: Shape = RoundedCornerShape(14.dp),
  val round: Shape = RoundedCornerShape(percent = 50),

  // Semantic aliases
  val button: Shape = RoundedCornerShape(8.dp),
  val textField: Shape = RoundedCornerShape(8.dp),
  val card: Shape = RoundedCornerShape(10.dp),
  val sidebarItem: Shape = RoundedCornerShape(8.dp),
  val badge: Shape = RoundedCornerShape(3.dp),
  val progressBar: Shape = RoundedCornerShape(2.dp),
  val dialog: Shape = RoundedCornerShape(12.dp),
)

fun ketchShapes(): KetchShapes = KetchShapes()
