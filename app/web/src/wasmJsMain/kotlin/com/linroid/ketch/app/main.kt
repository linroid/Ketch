package com.linroid.ketch.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.linroid.ketch.config.WebConfigStore
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.IncomingDownload
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val body = document.body ?: return
  val incoming = IncomingDownloads()
  // The browser holds files opened before this runs, so the first launch is not lost.
  consumeLaunchedFiles(
    limit = MAX_TORRENT_FILE_BYTES + 1,
    onFile = { name, base64 -> incoming.offerTorrentFile(name, Base64.decode(base64)) },
    onError = { name, message -> incoming.offer(IncomingDownload.Failed(name, message)) },
  )
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
        // Web has no embedded instance, so select by type rather than index.
        val first = instanceManager.instances.value
          .filterIsInstance<RemoteInstance>().firstOrNull()
        if (first != null) {
          scope.launch { instanceManager.switchTo(first) }
        }
      }
      onDispose { instanceManager.close() }
    }
    App(instanceManager, incoming = incoming)
  }
}

/**
 * Receives `.torrent` files opened with the installed web app, via the manifest's
 * `file_handlers` (Chromium browsers). Each file's first [limit] bytes reach [onFile] as base64.
 */
private fun consumeLaunchedFiles(
  limit: Int,
  onFile: (name: String, base64: String) -> Unit,
  onError: (name: String, message: String) -> Unit,
): Unit = js(
  """{
  if (!('launchQueue' in window)) return;
  window.launchQueue.setConsumer(async (params) => {
    for (const handle of params.files) {
      try {
        const file = await handle.getFile();
        const url = await new Promise((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result);
          reader.onerror = () => reject(reader.error);
          reader.readAsDataURL(file.slice(0, limit));
        });
        const comma = url.indexOf(',');
        onFile(handle.name, comma < 0 ? '' : url.substring(comma + 1));
      } catch (e) {
        onError(handle.name, String((e && e.message) || e));
      }
    }
  });
}"""
)

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
