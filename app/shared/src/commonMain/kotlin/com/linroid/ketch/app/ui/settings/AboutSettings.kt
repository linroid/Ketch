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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import com.linroid.ketch.app.components.KetchLogoTileDefaults
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.log.LogFilesAction
import com.linroid.ketch.app.log.rememberLogFilesAction
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import ketch.app.shared.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val PROJECT_URL = "https://github.com/linroid/Ketch"

private val log = KetchLogger("AboutSettings")

/**
 * Who made Ketch and which version this is, the licenses it ships under, its log files, and
 * ways to see the setup steps again.
 *
 * @param fileLogger the app's log files, or `null` when it keeps none.
 */
@Composable
fun AboutSettings(state: AppState, fileLogger: FileLogger? = null) {
  var showLicenses by remember { mutableStateOf(false) }
  val logFiles = fileLogger?.let { rememberLogFilesAction(it) }
  val appSettings = state.appSettings
  BrandHeader()
  if (!state.aiSettings.supported) {
    SettingsNotice(text = "Discover runs in the desktop and Android apps.", tone = NoticeTone.Info)
  }
  SettingsGroup {
    SettingsRow(title = "Version", trailing = { MonoValue(KetchApi.VERSION) })
    SettingsRow(title = "Build", trailing = { MonoValue(KetchApi.REVISION) })
  }
  SettingsGroup(title = "Project") {
    LinkRow(
      title = "Source code",
      description = "github.com/linroid/Ketch",
      url = PROJECT_URL,
    )
    LinkRow(
      title = "Report a problem",
      description = "Open an issue on GitHub",
      url = "$PROJECT_URL/issues",
    )
    SettingsRow(
      title = "Open-source licenses",
      modifier = Modifier.clickable(role = Role.Button) { showLicenses = true },
      trailing = { Chevron() },
    )
  }
  SettingsGroup(title = "Getting started") {
    // The mobile apps have welcome screens; their Downloads page shows no checklist.
    if (!isMobilePlatform) {
      var checklistShown by remember { mutableStateOf(false) }
      SettingsRow(
        title = "Show setup checklist",
        description = if (checklistShown) {
          "Done. It shows while the Downloads list is empty."
        } else {
          "The setup steps on an empty Downloads page."
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
        title = "Show welcome again",
        description = if (welcomeShown) {
          "Done. The welcome screens show next time you open Ketch."
        } else {
          "Where downloads go, and how you use Ketch on this device."
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
      title = "Troubleshooting",
      footer = "Logs include download names and links. Check them before posting.",
    ) {
      LogFilesRow(logFiles)
    }
  }
  if (showLicenses) {
    LicenseDialog(onDismiss = { showLicenses = false })
  }
}

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
    Text(
      text = "Ketch",
      style = KetchTheme.typography.pageTitle,
      color = colors.textPrimary,
      modifier = Modifier.padding(top = spacing.s2),
    )
    Text(
      text = "A ketch is a two-masted sailboat. Ketch splits every download into lanes, like " +
        "its sails.",
      style = KetchTheme.typography.bodyS,
      color = colors.textSecondary,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = BrandLineWidth),
    )
  }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
  var licenseText by remember { mutableStateOf("Loading licenses…") }
  LaunchedEffect(Unit) {
    licenseText = try {
      listOf("LICENSE.txt", "THIRD-PARTY-NOTICES.txt").map {
        Res.readBytes("files/licenses/$it").decodeToString()
      }.joinToString("\n\n")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't read the licenses: ${e.describeCauses()}" }
      "Couldn't load the licenses. They are in the LICENSE file at $PROJECT_URL."
    }
  }
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text("Open-source licenses") },
    confirmButton = {
      KetchButton(text = "Close", onClick = onDismiss, variant = KetchButtonVariant.Secondary)
    },
  ) {
    SelectionContainer {
      Text(
        text = licenseText,
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
  var failure by remember { mutableStateOf<String?>(null) }
  SettingsRow(
    title = action.title,
    description = failure ?: action.description,
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
          log.w { "Couldn't ${action.title.lowercase()}: ${e.describeCauses()}" }
          "Couldn't ${action.title.lowercase()}: ${e.message ?: e::class.simpleName}"
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

/** The chevron at the end of a row that opens something. */
@Composable
internal fun Chevron() {
  KetchIconImage(
    icon = KetchIcon.Chevron,
    size = KetchTheme.density.controlGlyph,
    tint = KetchTheme.colors.textTertiary,
  )
}

@Composable
private fun LinkRow(title: String, description: String, url: String) {
  val uriHandler = LocalUriHandler.current
  SettingsRow(
    title = title,
    description = description,
    modifier = Modifier.clickable(role = Role.Button) {
      try {
        uriHandler.openUri(url)
      } catch (e: Exception) {
        log.w { "Couldn't open ${redactUrl(url)}: ${e.describeCauses()}" }
      }
    },
    trailing = {
      KetchIconImage(
        icon = KetchIcon.Open,
        size = KetchTheme.density.controlGlyph,
        tint = KetchTheme.colors.textTertiary,
      )
    },
  )
}

private val BrandLineWidth = 360.dp
private val LicenseMaxHeight = 480.dp
