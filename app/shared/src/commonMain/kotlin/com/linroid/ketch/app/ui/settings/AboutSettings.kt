package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchLogoTile
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchLogoTileDefaults
import com.linroid.ketch.app.components.KetchProgressBar
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.log.LogFilesAction
import com.linroid.ketch.app.log.rememberLogFilesAction
import com.linroid.ketch.app.platform.AppUpdateState
import com.linroid.ketch.app.platform.AppUpdateStep
import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.platform.LocalAppUpdates
import com.linroid.ketch.app.platform.ReleaseNotesRequest
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_close
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.settings_about_build
import ketch.app.shared.generated.resources.settings_about_checklist
import ketch.app.shared.generated.resources.settings_about_checklist_done
import ketch.app.shared.generated.resources.settings_about_checklist_hint
import ketch.app.shared.generated.resources.settings_about_discover_elsewhere
import ketch.app.shared.generated.resources.settings_about_getting_started
import ketch.app.shared.generated.resources.settings_about_licenses
import ketch.app.shared.generated.resources.settings_about_licenses_failed
import ketch.app.shared.generated.resources.settings_about_licenses_loading
import ketch.app.shared.generated.resources.settings_about_logs_footer
import ketch.app.shared.generated.resources.settings_about_name_line
import ketch.app.shared.generated.resources.settings_about_project
import ketch.app.shared.generated.resources.settings_about_report
import ketch.app.shared.generated.resources.settings_about_report_hint
import ketch.app.shared.generated.resources.settings_about_source
import ketch.app.shared.generated.resources.settings_about_troubleshooting
import ketch.app.shared.generated.resources.settings_about_update_auto
import ketch.app.shared.generated.resources.settings_about_update_auto_hint
import ketch.app.shared.generated.resources.settings_about_update_available
import ketch.app.shared.generated.resources.settings_about_update_available_hint
import ketch.app.shared.generated.resources.settings_about_update_check
import ketch.app.shared.generated.resources.settings_about_update_check_failed
import ketch.app.shared.generated.resources.settings_about_update_checking
import ketch.app.shared.generated.resources.settings_about_update_current
import ketch.app.shared.generated.resources.settings_about_update_current_hint
import ketch.app.shared.generated.resources.settings_about_update_download_failed
import ketch.app.shared.generated.resources.settings_about_update_downloading
import ketch.app.shared.generated.resources.settings_about_update_idle
import ketch.app.shared.generated.resources.settings_about_update_idle_hint
import ketch.app.shared.generated.resources.settings_about_update_install
import ketch.app.shared.generated.resources.settings_about_update_install_failed
import ketch.app.shared.generated.resources.settings_about_update_notes
import ketch.app.shared.generated.resources.settings_about_update_open_installer
import ketch.app.shared.generated.resources.settings_about_update_progress
import ketch.app.shared.generated.resources.settings_about_update_ready
import ketch.app.shared.generated.resources.settings_about_update_ready_hint
import ketch.app.shared.generated.resources.settings_about_update_ready_open_hint
import ketch.app.shared.generated.resources.settings_about_update_release_page
import ketch.app.shared.generated.resources.settings_about_update_restart
import ketch.app.shared.generated.resources.settings_about_update_source_hint
import ketch.app.shared.generated.resources.settings_about_updates
import ketch.app.shared.generated.resources.settings_about_version
import ketch.app.shared.generated.resources.settings_about_welcome
import ketch.app.shared.generated.resources.settings_about_welcome_done
import ketch.app.shared.generated.resources.settings_about_welcome_hint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val PROJECT_URL = "https://github.com/linroid/Ketch"

private val log = KetchLogger("AboutSettings")

/**
 * Who made Ketch and which version this is, the licenses it ships under, its log files, and
 * ways to see the setup steps again. The log files are the [LocalFileLogger]'s, when the app
 * keeps them.
 */
@Composable
fun AboutSettings(state: AppState) {
  val fileLogger = LocalFileLogger.current
  var showLicenses by remember { mutableStateOf(false) }
  val logFiles = fileLogger?.let { rememberLogFilesAction(it) }
  val appSettings = state.appSettings
  BrandHeader()
  if (!state.aiSettings.supported) {
    SettingsNotice(
      text = stringResource(Res.string.settings_about_discover_elsewhere),
      tone = NoticeTone.Info,
    )
  }
  SettingsGroup {
    SettingsRow(
      title = stringResource(Res.string.settings_about_version),
      trailing = { MonoValue(KetchApi.VERSION) },
    )
    SettingsRow(
      title = stringResource(Res.string.settings_about_build),
      trailing = { MonoValue(KetchApi.REVISION) },
    )
  }
  LocalAppUpdates.current?.let { UpdatesGroup(it, appSettings) }
  SettingsGroup(title = stringResource(Res.string.settings_about_project)) {
    LinkRow(
      title = stringResource(Res.string.settings_about_source),
      description = "github.com/linroid/Ketch",
      url = PROJECT_URL,
    )
    LinkRow(
      title = stringResource(Res.string.settings_about_report),
      description = stringResource(Res.string.settings_about_report_hint),
      url = "$PROJECT_URL/issues",
    )
    SettingsRow(
      title = stringResource(Res.string.settings_about_licenses),
      modifier = Modifier.clickable(role = Role.Button) { showLicenses = true },
      trailing = { Chevron() },
    )
  }
  SettingsGroup(title = stringResource(Res.string.settings_about_getting_started)) {
    // The mobile apps have welcome screens; their Downloads page shows no checklist.
    if (!isMobilePlatform) {
      var checklistShown by remember { mutableStateOf(false) }
      SettingsRow(
        title = stringResource(Res.string.settings_about_checklist),
        description = if (checklistShown) {
          stringResource(Res.string.settings_about_checklist_done)
        } else {
          stringResource(Res.string.settings_about_checklist_hint)
        },
        modifier = Modifier.clickable(role = Role.Button) {
          appSettings.saveUi {
            it.copy(setupChecklistDismissed = false, setupChecklistShownAt = 0)
          }
          checklistShown = true
        },
        trailing = { Chevron() },
      )
    } else {
      var welcomeShown by remember { mutableStateOf(false) }
      SettingsRow(
        title = stringResource(Res.string.settings_about_welcome),
        description = if (welcomeShown) {
          stringResource(Res.string.settings_about_welcome_done)
        } else {
          stringResource(Res.string.settings_about_welcome_hint)
        },
        modifier = Modifier.clickable(role = Role.Button) {
          appSettings.saveUi { it.copy(onboardingVersion = 0) }
          welcomeShown = true
        },
        trailing = { Chevron() },
      )
    }
  }
  if (logFiles != null) {
    SettingsGroup(
      title = stringResource(Res.string.settings_about_troubleshooting),
      footer = stringResource(Res.string.settings_about_logs_footer),
    ) {
      LogFilesRow(logFiles)
    }
  }
  if (showLicenses) {
    LicenseDialog(onDismiss = { showLicenses = false })
  }
}

/**
 * Where the app's update to the latest release stands, with the button for the next step, what
 * is new in the release found or else in this one, and whether the app looks for one by itself.
 */
@Composable
private fun UpdatesGroup(updates: AppUpdates, appSettings: AppSettingsController) {
  val state by updates.state.collectAsState()
  var notes by remember { mutableStateOf<ReleaseNotesRequest?>(null) }
  SettingsGroup(title = stringResource(Res.string.settings_about_updates)) {
    UpdateStatusRow(state, updates)
    val current = updates.currentVersion
    // A newer release's notes start after this one's; otherwise this release's own.
    val request = state.releaseVersion()?.let { ReleaseNotesRequest(it, since = current) }
      ?: current?.let { ReleaseNotesRequest(it) }
    if (request != null) {
      SettingsRow(
        title = stringResource(Res.string.settings_about_update_notes, request.version),
        modifier = Modifier.clickable(role = Role.Button) { notes = request },
        trailing = { Chevron() },
      )
    }
    SettingsSwitchRow(
      title = stringResource(Res.string.settings_about_update_auto),
      description = stringResource(Res.string.settings_about_update_auto_hint),
      checked = appSettings.config.desktop.checkForUpdates,
      onCheckedChange = { on ->
        appSettings.saveDesktop { it.copy(checkForUpdates = on) }
        updates.setCheckAutomatically(on)
      },
    )
  }
  notes?.let { request ->
    ReleaseNotesDialog(updates, request, onDismiss = { notes = null })
  }
}

/** The version of the newer release [this] is about; `null` until one is found. */
private fun AppUpdateState.releaseVersion(): String? = when (this) {
  is AppUpdateState.Available -> version
  is AppUpdateState.Downloading -> version
  is AppUpdateState.Ready -> version
  is AppUpdateState.Failed -> version
  AppUpdateState.Idle, AppUpdateState.Checking, AppUpdateState.UpToDate -> null
}

@Composable
private fun UpdateStatusRow(state: AppUpdateState, updates: AppUpdates) {
  val colors = KetchTheme.colors
  val (title, description) = updateText(state)
  SettingsRow(
    title = title,
    description = description,
    descriptionColor = if (state is AppUpdateState.Failed) {
      colors.status.failed.color
    } else {
      colors.textSecondary
    },
    trailing = when (state) {
      AppUpdateState.Checking -> ({ KetchSpinner() })
      is AppUpdateState.Downloading -> null
      else -> ({ UpdateAction(state, updates) })
    },
    content = (state as? AppUpdateState.Downloading)?.let { downloading ->
      {
        val total = downloading.totalBytes.coerceAtLeast(1)
        KetchProgressBar(progress = downloading.downloadedBytes.toFloat() / total)
      }
    },
  )
}

/** The title and description of the status row in [state]. */
@Composable
private fun updateText(state: AppUpdateState): Pair<String, String?> = when (state) {
  AppUpdateState.Idle -> stringResource(Res.string.settings_about_update_idle) to
    stringResource(Res.string.settings_about_update_idle_hint)
  AppUpdateState.Checking -> stringResource(Res.string.settings_about_update_checking) to null
  AppUpdateState.UpToDate -> stringResource(Res.string.settings_about_update_current) to
    stringResource(Res.string.settings_about_update_current_hint, KetchApi.VERSION)
  is AppUpdateState.Available -> {
    val hint = if (state.installable) {
      stringResource(Res.string.settings_about_update_available_hint, KetchApi.VERSION)
    } else {
      stringResource(Res.string.settings_about_update_source_hint)
    }
    stringResource(Res.string.settings_about_update_available, state.version) to hint
  }
  is AppUpdateState.Downloading ->
    stringResource(Res.string.settings_about_update_downloading, state.version) to
      stringResource(
        Res.string.settings_about_update_progress,
        sizeText(state.downloadedBytes).resolve(),
        sizeText(state.totalBytes).resolve(),
      )
  is AppUpdateState.Ready -> {
    val hint = if (state.restarts) {
      stringResource(Res.string.settings_about_update_ready_hint)
    } else {
      stringResource(Res.string.settings_about_update_ready_open_hint)
    }
    stringResource(Res.string.settings_about_update_ready, state.version) to hint
  }
  is AppUpdateState.Failed -> stringResource(
    when (state.step) {
      AppUpdateStep.Check -> Res.string.settings_about_update_check_failed
      AppUpdateStep.Download -> Res.string.settings_about_update_download_failed
      AppUpdateStep.Install -> Res.string.settings_about_update_install_failed
    },
  ) to state.reason
}

/** The button for the next step in [state]. */
@Composable
private fun UpdateAction(state: AppUpdateState, updates: AppUpdates) {
  val uriHandler = LocalUriHandler.current
  val button = when (state) {
    is AppUpdateState.Available -> if (state.installable) {
      UpdateButton(Res.string.settings_about_update_install, primary = true, updates::download)
    } else {
      UpdateButton(Res.string.settings_about_update_release_page, primary = false) {
        openUri(uriHandler, state.notesUrl)
      }
    }
    is AppUpdateState.Ready -> UpdateButton(
      label = if (state.restarts) {
        Res.string.settings_about_update_restart
      } else {
        Res.string.settings_about_update_open_installer
      },
      primary = true,
      onClick = updates::install,
    )
    is AppUpdateState.Failed -> UpdateButton(
      Res.string.action_try_again,
      primary = false,
      onClick = when (state.step) {
        AppUpdateStep.Check -> updates::check
        AppUpdateStep.Download -> updates::download
        AppUpdateStep.Install -> updates::install
      },
    )
    else -> UpdateButton(Res.string.settings_about_update_check, primary = false, updates::check)
  }
  KetchButton(
    text = stringResource(button.label),
    onClick = button.onClick,
    variant = if (button.primary) KetchButtonVariant.Primary else KetchButtonVariant.Secondary,
    size = KetchButtonSize.Small,
  )
}

/** A button of the update row: what it says, whether it is the main action, what it does. */
private class UpdateButton(
  val label: StringResource,
  val primary: Boolean,
  val onClick: () -> Unit,
)

/** The app's tile and name over the one line about the name. */
@Composable
private fun BrandHeader() {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2),
  ) {
    KetchLogoTile(size = KetchLogoTileDefaults.About)
    // The product's name, never translated.
    Text(
      text = "Ketch",
      style = KetchTheme.typography.pageTitle,
      color = colors.textPrimary,
      modifier = Modifier.padding(top = spacing.s2),
    )
    Text(
      text = stringResource(Res.string.settings_about_name_line),
      style = KetchTheme.typography.bodyS,
      color = colors.textSecondary,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = BrandLineWidth),
    )
  }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
  // The licenses, or why they could not be read; null while they load.
  var licenses by remember { mutableStateOf<UiText?>(null) }
  LaunchedEffect(Unit) {
    licenses = try {
      verbatim(
        listOf("LICENSE.txt", "THIRD-PARTY-NOTICES.txt").map {
          Res.readBytes("files/licenses/$it").decodeToString()
        }.joinToString("\n\n"),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't read the licenses: ${e.describeCauses()}" }
      Res.string.settings_about_licenses_failed.text(PROJECT_URL)
    }
  }
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(Res.string.settings_about_licenses)) },
    confirmButton = {
      KetchButton(
        text = stringResource(Res.string.action_close),
        onClick = onDismiss,
        variant = KetchButtonVariant.Secondary,
      )
    },
  ) {
    SelectionContainer {
      Text(
        text = licenses?.resolve() ?: stringResource(Res.string.settings_about_licenses_loading),
        style = KetchTheme.typography.mono,
        color = KetchTheme.colors.textSecondary,
        modifier = Modifier.heightIn(max = LicenseMaxHeight).verticalScroll(rememberScrollState()),
      )
    }
  }
}

/** Opens or shares the log files; a failure replaces the description until the next try. */
@Composable
private fun LogFilesRow(action: LogFilesAction) {
  val scope = rememberCoroutineScope()
  var running by remember { mutableStateOf(false) }
  var failure by remember { mutableStateOf<UiText?>(null) }
  SettingsRow(
    title = action.title.resolve(),
    description = (failure ?: action.description).resolve(),
    descriptionColor = if (failure != null) {
      KetchTheme.colors.status.failed.color
    } else {
      KetchTheme.colors.textSecondary
    },
    modifier = Modifier.clickable(enabled = !running, role = Role.Button) {
      running = true
      scope.launch {
        failure = try {
          action.run()
          null
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.w { "Couldn't hand over the log files: ${e.describeCauses()}" }
          action.failed.text(e.message ?: e::class.simpleName.orEmpty())
        } finally {
          running = false
        }
      }
    },
    trailing = { Chevron() },
  )
}

@Composable
private fun MonoValue(text: String) {
  Text(text = text, style = KetchTheme.typography.mono, color = KetchTheme.colors.textSecondary)
}

@Composable
private fun LinkRow(title: String, description: String, url: String) {
  val uriHandler = LocalUriHandler.current
  SettingsRow(
    title = title,
    description = description,
    modifier = Modifier.clickable(role = Role.Button) { openUri(uriHandler, url) },
    trailing = {
      KetchIconImage(
        icon = KetchIcon.Open,
        size = KetchTheme.density.controlGlyph,
        tint = KetchTheme.colors.textTertiary,
      )
    },
  )
}

/** Opens [url] with [uriHandler]; a failure is only logged. */
internal fun openUri(uriHandler: UriHandler, url: String) {
  try {
    uriHandler.openUri(url)
  } catch (e: Exception) {
    log.w { "Couldn't open ${redactUrl(url)}: ${e.describeCauses()}" }
  }
}

private val BrandLineWidth = 360.dp
private val LicenseMaxHeight = 480.dp
