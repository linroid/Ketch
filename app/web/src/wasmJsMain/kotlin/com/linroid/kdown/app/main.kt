package com.linroid.kdown.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.linroid.kdown.app.instance.InstanceFactory
import com.linroid.kdown.app.instance.InstanceManager
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val body = document.body ?: return
  ComposeViewport(body) {
    val instanceManager = remember {
      InstanceManager(InstanceFactory())
    }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
      if (shouldAutoConnect()) {
        val host = window.location.hostname
        val port = window.location.port.toIntOrNull() ?: 80
        val entry = instanceManager.addRemote(host, port)
        scope.launch { instanceManager.switchTo(entry) }
      }
      onDispose { instanceManager.close() }
    }
    App(instanceManager)
  }
}

/**
 * Returns `true` when the page contains
 * `<meta name="kdown-auto-connect" content="true">`,
 * which the CLI server injects when bundling the web UI.
 */
private fun shouldAutoConnect(): Boolean {
  val meta = document.querySelector(
    "meta[name='kdown-auto-connect']"
  ) ?: return false
  return meta.getAttribute("content") == "true"
}
