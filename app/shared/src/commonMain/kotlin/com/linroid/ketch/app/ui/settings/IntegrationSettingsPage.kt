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
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
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
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_integration_clipboard
import ketch.app.shared.generated.resources.settings_integration_clipboard_fill
import ketch.app.shared.generated.resources.settings_integration_clipboard_fill_hint
import ketch.app.shared.generated.resources.settings_integration_clipboard_off
import ketch.app.shared.generated.resources.settings_integration_clipboard_off_hint
import ketch.app.shared.generated.resources.settings_integration_clipboard_suggest
import ketch.app.shared.generated.resources.settings_integration_clipboard_suggest_choice
import ketch.app.shared.generated.resources.settings_integration_clipboard_suggest_hint
import ketch.app.shared.generated.resources.settings_integration_connected
import ketch.app.shared.generated.resources.settings_integration_default_apps
import ketch.app.shared.generated.resources.settings_integration_extension
import ketch.app.shared.generated.resources.settings_integration_extension_footer
import ketch.app.shared.generated.resources.settings_integration_get_extension
import ketch.app.shared.generated.resources.settings_integration_is_default
import ketch.app.shared.generated.resources.settings_integration_magnet
import ketch.app.shared.generated.resources.settings_integration_magnet_failed
import ketch.app.shared.generated.resources.settings_integration_magnet_refused
import ketch.app.shared.generated.resources.settings_integration_make_default
import ketch.app.shared.generated.resources.settings_integration_no_browser
import ketch.app.shared.generated.resources.settings_integration_no_browser_hint
import ketch.app.shared.generated.resources.settings_integration_quick_add
import ketch.app.shared.generated.resources.settings_integration_quick_add_hint
import ketch.app.shared.generated.resources.settings_integration_quick_add_key_hint
import ketch.app.shared.generated.resources.settings_integration_torrent
import ketch.app.shared.generated.resources.settings_integration_torrent_failed
import ketch.app.shared.generated.resources.settings_integration_torrent_refused
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

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
      text = stringResource(Res.string.settings_integration_get_extension),
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
    title = stringResource(Res.string.settings_integration_extension),
    footer = stringResource(Res.string.settings_integration_extension_footer),
  ) {
    if (status.browsers.isEmpty()) {
      SettingsRow(
        title = stringResource(Res.string.settings_integration_no_browser),
        description = stringResource(Res.string.settings_integration_no_browser_hint),
        trailing = getExtension,
      )
    }
    status.browsers.forEach { browser ->
      SettingsRow(
        title = browser.name,
        trailing = if (browser.extensionConnected) {
          { Confirmed(stringResource(Res.string.settings_integration_connected)) }
        } else {
          getExtension
        },
      )
    }
  }
}

/**
 * A kind of link or file Ketch can open.
 *
 * @property logName how the log names it.
 * @property refused what the page says when the system did not make Ketch open it.
 * @property failed what the page says when asking the system failed; `%1$s` is why.
 */
private enum class Handled(
  val logName: String,
  val refused: StringResource,
  val failed: StringResource,
) {
  MagnetLinks(
    logName = "magnet",
    refused = Res.string.settings_integration_magnet_refused,
    failed = Res.string.settings_integration_magnet_failed,
  ),
  TorrentFiles(
    logName = ".torrent",
    refused = Res.string.settings_integration_torrent_refused,
    failed = Res.string.settings_integration_torrent_failed,
  ),
}

/** Whether Ketch opens magnet links and `.torrent` files, with buttons that make it. */
@Composable
private fun DefaultAppsGroup(state: AppState) {
  val hooks = LocalDesktopHooks.current
  val status = LocalIntegrationStatus.current
  val appSettings = state.appSettings
  var registering by remember { mutableStateOf<Handled?>(null) }
  // What this page registered, shown as done before the system's answer is read again, which
  // happens only when the window comes back to the front.
  var registered by remember { mutableStateOf(emptySet<Handled>()) }
  var failure by remember { mutableStateOf<UiText?>(null) }
  val register = { what: Handled, hook: suspend () -> Boolean, save: IntegrationChange ->
    registering = what
    failure = null
    state.launchCommand {
      try {
        if (hook()) {
          appSettings.saveIntegration(save)
          registered = registered + what
        } else {
          log.w { "The system didn't make Ketch open ${what.logName} links or files" }
          failure = what.refused.text()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't register for ${what.logName}: ${e.describeCauses()}" }
        failure = what.failed.text(e.message ?: e::class.simpleName.orEmpty())
      } finally {
        registering = null
      }
    }
  }
  SettingsGroup(
    title = stringResource(Res.string.settings_integration_default_apps),
    footer = failure?.resolve(),
  ) {
    DefaultAppRow(
      title = stringResource(Res.string.settings_integration_magnet),
      isDefault = status.magnetHandler || Handled.MagnetLinks in registered,
      registering = registering == Handled.MagnetLinks,
      onMakeDefault = {
        register(Handled.MagnetLinks, hooks::registerMagnetHandler) {
          it.copy(magnetHandler = true)
        }
      },
    )
    DefaultAppRow(
      title = stringResource(Res.string.settings_integration_torrent),
      isDefault = status.torrentFileHandler || Handled.TorrentFiles in registered,
      registering = registering == Handled.TorrentFiles,
      onMakeDefault = {
        register(Handled.TorrentFiles, hooks::registerTorrentFileHandler) {
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
        Confirmed(stringResource(Res.string.settings_integration_is_default))
      } else {
        KetchButton(
          text = stringResource(Res.string.settings_integration_make_default),
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
  SettingsGroup(title = stringResource(Res.string.settings_integration_clipboard)) {
    SettingsRow(
      title = stringResource(Res.string.settings_integration_clipboard_suggest),
      description = stringResource(
        when (mode) {
          ClipboardMode.Fill -> Res.string.settings_integration_clipboard_fill_hint
          ClipboardMode.Suggest -> Res.string.settings_integration_clipboard_suggest_hint
          ClipboardMode.Off -> Res.string.settings_integration_clipboard_off_hint
        },
      ),
      trailing = {
        KetchSegmented(
          selected = mode,
          options = ClipboardMode.entries,
          label = { option ->
            stringResource(
              when (option) {
                ClipboardMode.Fill -> Res.string.settings_integration_clipboard_fill
                ClipboardMode.Suggest -> Res.string.settings_integration_clipboard_suggest_choice
                ClipboardMode.Off -> Res.string.settings_integration_clipboard_off
              },
            )
          },
          onSelect = { option -> appSettings.saveUi { it.copy(clipboardMode = option) } },
        )
      },
    )
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_integration_quick_add),
      description = if (paste != null) {
        stringResource(Res.string.settings_integration_quick_add_key_hint, paste)
      } else {
        stringResource(Res.string.settings_integration_quick_add_hint)
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
