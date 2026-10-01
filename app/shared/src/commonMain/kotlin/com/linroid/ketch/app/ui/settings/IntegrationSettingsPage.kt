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
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.ClipboardMode
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
      onClick = { runCatching { uriHandler.openUri(EXTENSION_URL) } },
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
      leadingIcon = KetchIcon.Open,
    )
  }
  SettingsGroup(
    title = "Browser extension",
    footer = "The extension sends your browser's downloads to Ketch with the site's cookies, " +
      "so downloads that need you to be signed in work too. It opens Ketch when it needs it.",
  ) {
    if (status.browsers.isEmpty()) {
      SettingsRow(
        title = "No browser found yet",
        description = "Works with Chrome, Edge, Brave, Firefox and other Chromium browsers.",
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
  var failure by remember { mutableStateOf<String?>(null) }
  val register = { what: String, block: suspend () -> Unit ->
    registering = what
    failure = null
    state.launchCommand {
      try {
        block()
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
    SettingsRow(
      title = "Open magnet links with Ketch",
      description = "Magnet links you click in any app start a download here.",
      trailing = {
        if (status.magnetHandler) {
          Confirmed("Ketch is the default")
        } else {
          KetchButton(
            text = "Make default",
            onClick = {
              register(MAGNET_LINKS) {
                if (hooks.registerMagnetHandler()) {
                  appSettings.saveIntegration { it.copy(magnetHandler = true) }
                }
              }
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            loading = registering == MAGNET_LINKS,
          )
        }
      },
    )
    SettingsRow(
      title = "Open .torrent files with Ketch",
      description = "Opening a .torrent file shows it in Ketch, ready to add.",
      trailing = {
        if (status.torrentFileHandler) {
          Confirmed("Ketch is the default")
        } else {
          KetchButton(
            text = "Make default",
            onClick = {
              register(TORRENT_FILES) {
                if (hooks.registerTorrentFileHandler()) {
                  appSettings.saveIntegration { it.copy(torrentFileHandler = true) }
                }
              }
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            loading = registering == TORRENT_FILES,
          )
        }
      },
    )
  }
}

/** What Ketch does with a copied link, and with one pasted into the list. */
@Composable
private fun ClipboardGroup(appSettings: AppSettingsController) {
  val mode = clipboardModeOf(appSettings)
  val quickAdd = appSettings.ui.quickAdd ?: !isMobilePlatform
  val paste = KetchCommands.PasteLinks.shortcutLabel(KeyboardPlatform.current)
  SettingsGroup(title = "Clipboard") {
    SettingsRow(
      title = "Suggest links from the clipboard",
      description = when (mode) {
        ClipboardMode.Fill -> "A copied link fills the add sheet when you open it."
        ClipboardMode.Suggest -> "Ketch offers a copied link, and reads the clipboard only " +
          "when you tap it."
        ClipboardMode.Off -> "Ketch never looks at the clipboard."
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
        "Pasting one link into the list with $paste adds it at once, with Undo."
      } else {
        "Pasting one link into the list adds it at once, with Undo."
      },
      checked = quickAdd,
      onCheckedChange = { on -> appSettings.saveUi { it.copy(quickAdd = on) } },
    )
  }
}

/**
 * What to do with a link on the clipboard: the setting, else fill on desktop, else suggest, as
 * the add sheet reads it.
 */
internal fun clipboardModeOf(appSettings: AppSettingsController): ClipboardMode =
  appSettings.ui.clipboardMode
    ?: if (isMobilePlatform || KeyboardPlatform.current.isWeb) {
      ClipboardMode.Suggest
    } else {
      ClipboardMode.Fill
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

private const val MAGNET_LINKS = "magnet links"
private const val TORRENT_FILES = ".torrent files"
