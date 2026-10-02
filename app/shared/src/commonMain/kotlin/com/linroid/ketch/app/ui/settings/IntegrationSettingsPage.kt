package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.clipboardMode
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.ClipboardMode
import com.linroid.ketch.config.IntegrationSettings
import kotlinx.coroutines.CancellationException

private val log = KetchLogger("IntegrationSettings")

/** Where the browser extension and its install steps live. */
private const val EXTENSION_URL =
  "https://github.com/linroid/Ketch/tree/main/app/browser-extension#installing"

/**
 * How Ketch takes links from elsewhere: on desktop the browser extension in each browser found
 * and whether Ketch opens magnet links and `.torrent` files; everywhere what it does with copied
 * and pasted links.
 */
@Composable
fun IntegrationSettingsPage(state: AppState) {
  val appSettings = state.appSettings
  if (LocalDesktopHooks.current.isSupported) {
    BrowserGroup()
    DefaultAppsGroup(state)
  }
  ClipboardGroup(appSettings)
}

/** One row per browser found, connected or with a link to the extension. */
@Composable
private fun BrowserGroup() {
  val status = LocalIntegrationStatus.current
  val uriHandler = LocalUriHandler.current
  val getExtension = @Composable {
    KetchButton(
      text = "Get extension",
      onClick = {
        try {
          uriHandler.openUri(EXTENSION_URL)
        } catch (e: Exception) {
          log.w { "Couldn't open the extension's page: ${e.describeCauses()}" }
        }
      },
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
      leadingIcon = KetchIcon.Open,
    )
  }
  SettingsGroup(
    title = "Browser extension",
    footer = "Sends your browser's downloads to Ketch, with the site's cookies.",
  ) {
    if (status.browsers.isEmpty()) {
      SettingsRow(
        title = "No browser found yet",
        description = "For Chrome, Edge, Brave, Firefox and other Chromium browsers.",
        trailing = getExtension,
      )
    }
    status.browsers.forEach { browser ->
      SettingsRow(
        title = browser.name,
        trailing = if (browser.extensionConnected) {
          { Confirmed("Connected") }
        } else {
          getExtension
        },
      )
    }
  }
}

/** Whether Ketch opens magnet links and `.torrent` files, with buttons that make it. */
@Composable
private fun DefaultAppsGroup(state: AppState) {
  val hooks = LocalDesktopHooks.current
  val status = LocalIntegrationStatus.current
  val appSettings = state.appSettings
  var registering by remember { mutableStateOf<String?>(null) }
  // What this page registered, shown as done before the system's answer is read again, which
  // happens only when the window comes back to the front.
  var registered by remember { mutableStateOf(emptySet<String>()) }
  var failure by remember { mutableStateOf<String?>(null) }
  val register = { what: String, hook: suspend () -> Boolean, save: IntegrationChange ->
    registering = what
    failure = null
    state.launchCommand {
      try {
        if (hook()) {
          appSettings.saveIntegration(save)
          registered = registered + what
        } else {
          log.w { "The system didn't make Ketch open $what" }
          failure = "Ketch couldn't make itself open $what. Choose it in your system's settings."
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't register for $what: ${e.describeCauses()}" }
        failure = "Couldn't make Ketch open $what: ${e.message ?: e::class.simpleName}"
      } finally {
        registering = null
      }
    }
  }
  SettingsGroup(title = "Default apps", footer = failure) {
    DefaultAppRow(
      title = "Open magnet links with Ketch",
      isDefault = status.magnetHandler || MAGNET_LINKS in registered,
      registering = registering == MAGNET_LINKS,
      onMakeDefault = {
        register(MAGNET_LINKS, hooks::registerMagnetHandler) { it.copy(magnetHandler = true) }
      },
    )
    DefaultAppRow(
      title = "Open .torrent files with Ketch",
      isDefault = status.torrentFileHandler || TORRENT_FILES in registered,
      registering = registering == TORRENT_FILES,
      onMakeDefault = {
        register(TORRENT_FILES, hooks::registerTorrentFileHandler) {
          it.copy(torrentFileHandler = true)
        }
      },
    )
  }
}

/** A kind of link or file Ketch can open: done when [isDefault], else a Make default button. */
@Composable
private fun DefaultAppRow(
  title: String,
  isDefault: Boolean,
  registering: Boolean,
  onMakeDefault: () -> Unit,
) {
  SettingsRow(
    title = title,
    trailing = {
      if (isDefault) {
        Confirmed("Ketch is the default")
      } else {
        KetchButton(
          text = "Make default",
          onClick = onMakeDefault,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          loading = registering,
        )
      }
    },
  )
}

/** What Ketch does with a copied link, and with one pasted into the list. */
@Composable
private fun ClipboardGroup(appSettings: AppSettingsController) {
  val mode = appSettings.clipboardMode
  val quickAdd = appSettings.ui.quickAdd ?: !isMobilePlatform
  val paste = KetchCommands.PasteLinks.shortcutLabel(KeyboardPlatform.current)
  SettingsGroup(title = "Clipboard") {
    SettingsRow(
      title = "Suggest links from the clipboard",
      description = when (mode) {
        ClipboardMode.Fill -> "A copied link fills the add sheet."
        ClipboardMode.Suggest -> "Reads the clipboard only when you tap."
        ClipboardMode.Off -> "Ketch never reads the clipboard."
      },
      trailing = {
        SettingsSegmented(
          value = mode,
          options = ClipboardMode.entries,
          label = { option ->
            when (option) {
              ClipboardMode.Fill -> "Fill automatically"
              ClipboardMode.Suggest -> "Suggest"
              ClipboardMode.Off -> "Off"
            }
          },
          onSelect = { option -> appSettings.saveUi { it.copy(clipboardMode = option) } },
        )
      },
    )
    SettingsSwitchRow(
      title = "Add pasted links immediately",
      description = if (paste != null) {
        "Pasting one link with $paste adds it at once, with Undo."
      } else {
        "Pasting one link adds it at once, with Undo."
      },
      checked = quickAdd,
      onCheckedChange = { on -> appSettings.saveUi { it.copy(quickAdd = on) } },
    )
  }
}

/** A check and [text] in the success color, for something already set up. */
@Composable
private fun Confirmed(text: String) {
  val color = KetchTheme.colors.status.completed.color
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
  ) {
    Text(text = text, style = KetchTheme.typography.label, color = color, maxLines = 1)
    KetchIconImage(icon = KetchIcon.Check, size = KetchTheme.density.controlGlyph, tint = color)
  }
}

private typealias IntegrationChange = (IntegrationSettings) -> IntegrationSettings

private const val MAGNET_LINKS = "magnet links"
private const val TORRENT_FILES = ".torrent files"
