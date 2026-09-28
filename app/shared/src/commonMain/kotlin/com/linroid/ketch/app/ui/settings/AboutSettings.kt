package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.log.LogFilesAction
import com.linroid.ketch.app.log.rememberLogFilesAction
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val PROJECT_URL = "https://github.com/linroid/Ketch"

/**
 * Version information, project links and the app's log files.
 *
 * @param fileLogger the app's log files, or `null` when it keeps none.
 */
@Composable
fun AboutSettings(fileLogger: FileLogger? = null) {
  var showLicenses by remember { mutableStateOf(false) }
  val logFiles = fileLogger?.let { rememberLogFilesAction(it) }
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
        description = "Ketch and third-party notices",
        modifier = Modifier.clickable(role = Role.Button) { showLicenses = true },
      )
    }
    if (logFiles != null) {
      SettingsGroup(
        title = "Troubleshooting",
        footer = "Logs include the names and addresses of your downloads, with passwords " +
          "masked. Check them before posting them publicly.",
      ) {
        LogFilesRow(logFiles)
      }
    }
  }
  if (showLicenses) {
    LicenseDialog(onDismiss = { showLicenses = false })
  }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
  var licenseText by remember { mutableStateOf("Loading licenses…") }
  LaunchedEffect(Unit) {
    licenseText = listOf("LICENSE.txt", "THIRD-PARTY-NOTICES.txt").map {
      Res.readBytes("files/licenses/$it").decodeToString()
    }.joinToString("\n\n")
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Open-source licenses") },
    text = {
      SelectionContainer {
        Text(
          text = licenseText,
          modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
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
      KetchTheme.colors.error
    } else {
      KetchTheme.colors.onSurfaceVariant
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
          "Couldn't ${action.title.lowercase()}: ${e.message ?: e::class.simpleName}"
        } finally {
          running = false
        }
      }
    },
    trailing = {
      KetchIconImage(KetchIcon.Chevron, size = 14.dp, tint = KetchTheme.colors.onSurfaceDim)
    },
  )
}

@Composable
private fun MonoValue(text: String) {
  Text(
    text = text,
    style = KetchTheme.typography.monoSmall,
    color = KetchTheme.colors.onSurfaceVariant,
  )
}

@Composable
private fun LinkRow(title: String, description: String, url: String) {
  val uriHandler = LocalUriHandler.current
  SettingsRow(
    title = title,
    description = description,
    modifier = Modifier.clickable(role = Role.Button) {
      runCatching { uriHandler.openUri(url) }
    },
    trailing = {
      KetchIconImage(KetchIcon.Chevron, size = 14.dp, tint = KetchTheme.colors.onSurfaceDim)
    },
  )
}
