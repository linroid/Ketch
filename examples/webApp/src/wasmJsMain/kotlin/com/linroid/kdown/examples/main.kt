package com.linroid.kdown.examples

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.linroid.kdown.examples.backend.BackendFactory
import com.linroid.kdown.examples.backend.BackendManager
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val body = document.body ?: return
  ComposeViewport(body) {
    val backendManager = remember {
      BackendManager(BackendFactory())
    }
    DisposableEffect(Unit) {
      onDispose { backendManager.close() }
    }
    App(backendManager)
  }
}
