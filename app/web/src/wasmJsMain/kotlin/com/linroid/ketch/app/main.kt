package com.linroid.ketch.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.linroid.ketch.config.WebConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val body = document.body ?: return
  ComposeViewport(body) {
    val configStore = remember { WebConfigStore() }
    val config = remember { configStore.load() }
    val instanceManager = remember {
      InstanceManager(
        factory = InstanceFactory(),
        initialRemotes = config.remotes,
        configStore = configStore,
      )
    }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
      // Auto-connect from config remotes or meta tag
      if (config.remotes.isEmpty() && shouldAutoConnect()) {
        val host = window.location.hostname
        val port = window.location.port.toIntOrNull() ?: 80
        val entry = instanceManager.addRemote(host, port)
        scope.launch { instanceManager.switchTo(entry) }
      } else if (config.remotes.isNotEmpty()) {
        val first = instanceManager.instances.value
          .drop(1).firstOrNull() // skip embedded (null), take first remote
        if (first != null) {
          scope.launch { instanceManager.switchTo(first) }
        }
      }
      onDispose { instanceManager.close() }
    }
    App(instanceManager)
  }
}

/**
 * Returns `true` when the page contains
 * `<meta name="ketch-auto-connect" content="true">`,
 * which the CLI server injects when bundling the web UI.
 */
private fun shouldAutoConnect(): Boolean {
  val meta = document.querySelector(
    "meta[name='ketch-auto-connect']"
  ) ?: return false
  return meta.getAttribute("content") == "true"
}
