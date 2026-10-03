@file:OptIn(ExperimentalWasmJsInterop::class)

package com.linroid.ketch.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.ComposeViewport
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.feedback.reportWebActivity
import com.linroid.ketch.app.i18n.appLanguageTag
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.IncomingDownload
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.LinkSource
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.theme.lightKetchColors
import com.linroid.ketch.app.theme.rememberKetchFontsLoaded
import com.linroid.ketch.app.ui.connect.connectTo
import com.linroid.ketch.app.util.PairingLink
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.ThemeMode
import com.linroid.ketch.config.WebConfigStore
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val body = document.body ?: return
  // No embedded Ketch installs a logger here, so the remote client's logs need one.
  KetchLogger.setLogger(Logger.console(LogLevel.INFO))
  val incoming = IncomingDownloads()
  // The server that serves this page. A pairing link to it (`http://host:port/#token=…`) holds
  // the access code in the fragment, which leaves the address before anything can show or keep
  // it.
  val launchUrl = window.location.href
  val page = PairingLink.parse(launchUrl)
  clearPairingCode()
  // Links the page was opened with, from the magnet protocol handler or the share target. They
  // leave the address at once, so a reload does not add them again.
  incoming.offerLaunchLinks(launchUrl)
  clearLaunchLinks()
  var loadLaunch: String? = launchUrl
  // The browser holds files and links opened before this runs, so the first launch is not lost.
  consumeLaunches(
    limit = MAX_TORRENT_FILE_BYTES + 1,
    onUrl = { url ->
      // The launch that loaded the page is queued too; its links were read from the address.
      val skip = url == loadLaunch
      loadLaunch = null
      if (!skip) incoming.offerLaunchLinks(url)
    },
    onFile = { name, base64 -> incoming.offerTorrentFile(name, Base64.decode(base64)) },
    onError = { name, message -> incoming.offer(IncomingDownload.Failed(name, message)) },
  )
  val configStore = WebConfigStore()
  val config = configStore.load()
  val instanceManager = InstanceManager(
    factory = InstanceFactory(),
    initialRemotes = config.remotes,
    configStore = configStore,
  )
  // The page owns the controller and the activity monitor for as long as it is open.
  val controller = AppController(instanceManager, incoming = incoming)
  connectOnLoad(controller, page)
  val activityEvents = reportWebActivity(controller)
  // Removals and other undoable operations still pending commit when the tab closes.
  window.addEventListener("pagehide", { controller.state.pendingOps.flush() })
  // The page's language and title follow the language the app shows, which Settings can change.
  controller.scope.launch {
    snapshotFlow { controller.appSettings.language }.collectLatest {
      setPageLanguage()
      controller.pulse.state.collect { document.title = it.tabTitle().load() }
    }
  }
  ComposeViewport(body) {
    // The installed app's title bar takes the canvas color of the theme the app shows.
    val themeMode = controller.appSettings.themeMode
    LaunchedEffect(themeMode) {
      val light = lightKetchColors().canvas.toHex()
      val dark = darkKetchColors().canvas.toHex()
      when (themeMode) {
        ThemeMode.System -> setThemeColors(light, dark)
        ThemeMode.Light -> setThemeColors(light, light)
        ThemeMode.Dark -> setThemeColors(dark, dark)
      }
    }
    // The splash in index.html stays until the fonts are cached, so text never flashes in a
    // fallback font. A font that fails to load must not keep the app hidden.
    var fontWaitOver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
      delay(FONT_WAIT_LIMIT)
      fontWaitOver = true
    }
    if (rememberKetchFontsLoaded() || fontWaitOver) {
      LaunchedEffect(Unit) {
        withFrameNanos {}
        document.getElementById("splash")?.remove()
      }
      App(controller, activityEvents = activityEvents)
    }
  }
}

private val FONT_WAIT_LIMIT = 5.seconds

/**
 * Sets the page's `lang` to the language of the strings the app shows: the browser's when the app
 * is translated to it, else English. Screen readers then read the page in that language, and the
 * browser offers to translate it only when it is not in the browser's language.
 *
 * The bundled fonts only cover Latin, Greek and Cyrillic. For Chinese, Japanese, Korean and
 * other scripts, Compose (1.12 and later) downloads Noto fonts from Google Fonts as text needs
 * them, choosing the Simplified Chinese, Traditional Chinese, Hong Kong, Japanese or Korean
 * variant by the browser's language, so the app ships no font for them.
 */
private suspend fun setPageLanguage() {
  document.documentElement?.setAttribute("lang", appLanguageTag())
}

/** [this] as `#RRGGBB`. */
private fun Color.toHex(): String = "#" + (toArgb() and RGB_MASK).toString(HEX).padStart(6, '0')

private const val RGB_MASK = 0xFFFFFF
private const val HEX = 16

/**
 * Sets the `theme-color` metas of index.html, which color the installed app's title bar: [light]
 * where the system is light and [dark] where it is dark.
 */
private fun setThemeColors(light: String, dark: String): Unit = js(
  """{
  for (const meta of document.querySelectorAll("meta[name='theme-color']")) {
    const isDark = (meta.getAttribute('media') || '').includes('dark');
    meta.setAttribute('content', isDark ? dark : light);
  }
}"""
)

/**
 * Connects to the server of the [page] when the address carried its access code, or when
 * nothing is saved and the page was served by that server ([shouldAutoConnect]); otherwise
 * shows the first saved device, unless the one shown last time is shown again. With none of
 * these, the app shows its connect page.
 */
private fun connectOnLoad(controller: AppController, page: PairingLink?) {
  val state = controller.state
  val manager = state.instanceManager
  // Web has no embedded instance, so every device is a remote one.
  val remotes = manager.instances.value.filterIsInstance<RemoteInstance>()
  when {
    page?.token != null -> controller.scope.launch { state.connectTo(page) }
    page != null && remotes.isEmpty() && shouldAutoConnect() -> {
      val server = RemoteConfig(host = page.host, port = page.port, secure = page.secure)
      state.switchInstance(manager.addRemote(server))
    }
    manager.activeInstance.value == null -> remotes.firstOrNull()?.let(state::switchInstance)
  }
}

/**
 * Offers the links [url] carries: `add` holds a link from the magnet protocol handler or a shared
 * link, and `text` shared text with links in it (see the manifest's `share_target`).
 */
private fun IncomingDownloads.offerLaunchLinks(url: String) {
  val links = queryValues(url, "add")
  val shared = queryValues(url, "text")
  val text = listOf(links, shared).filter { it.isNotBlank() }.joinToString("\n")
  if (text.isEmpty()) return
  offerText(text, if (shared.isBlank()) LinkSource.OpenUrl else LinkSource.Share)
}

/** Every value of the query parameter [name] in [url], one per line. */
private fun queryValues(url: String, name: String): String =
  js("""new URL(url).searchParams.getAll(name).join('\n')""")

/** Removes the access code from the address's fragment, keeping its history entry. */
private fun clearPairingCode(): Unit = js(
  """{
  const url = new URL(window.location.href);
  const fragment = new URLSearchParams(url.hash.substring(1));
  if (fragment.has('token')) {
    fragment.delete('token');
    const rest = fragment.toString();
    url.hash = rest ? '#' + rest : '';
    window.history.replaceState(window.history.state, '', url.href);
  }
}"""
)

/** Removes the launch links' parameters from the address, keeping its history entry. */
private fun clearLaunchLinks(): Unit = js(
  """{
  const url = new URL(window.location.href);
  url.searchParams.delete('add');
  url.searchParams.delete('text');
  if (url.href !== window.location.href) {
    window.history.replaceState(window.history.state, '', url.href);
  }
}"""
)

/**
 * Receives launches of the installed web app while it runs or starts (Chromium browsers): the
 * address of each one reaches [onUrl], which may carry links (see [offerLaunchLinks]), and
 * `.torrent` files opened with it through the manifest's `file_handlers` reach [onFile], the
 * first [limit] bytes of each as base64.
 */
private fun consumeLaunches(
  limit: Int,
  onUrl: (url: String) -> Unit,
  onFile: (name: String, base64: String) -> Unit,
  onError: (name: String, message: String) -> Unit,
): Unit = js(
  """{
  if (!('launchQueue' in window)) return;
  window.launchQueue.setConsumer(async (params) => {
    if (params.targetURL) onUrl(params.targetURL);
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
