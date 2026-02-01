package com.linroid.kdown.examples

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "KDown Examples"
  ) {
    App()
  }
}
